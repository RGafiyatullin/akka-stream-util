
import akka.actor.{ActorRef, Status}
import akka.stream._
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.Timeout
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts.{InletPushedContext, OutletPulledContext, PreStartContext, ReceiveContext}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._

object CountedIdentityFlowTest {

  object CountedIdentityFlow {
    private object messages {
      case object Get
      case object Reset
    }

    object State {
      def empty[Item](shape: FlowShape[Item, Item]): State[Item] = State(0, shape)
    }

    final case class State[Item](count: Long = 0, shape: FlowShape[Item, Item])
      extends Stage.State[CountedIdentityFlow[Item]]
    {
      override def receiveEnabled: Boolean = true

      val apiPromise: Promise[Api] = Promise()
      val apiFuture: Future[Api] = apiPromise.future

      def withCount(countNext: Long): State[Item] =
        copy(count = countNext)
      def mapCount(f: Long => Long): State[Item] =
        withCount(f(count))

      override def preStart(ctx: PreStartContext[CountedIdentityFlow[Item]]): PreStartContext[CountedIdentityFlow[Item]] = {
        apiPromise.success(Api(ctx.stageActorRef))
        ctx
      }


      override def outletOnPull
        (ctx: OutletPulledContext[CountedIdentityFlow[Item]])
      : OutletPulledContext[CountedIdentityFlow[Item]] =
        ctx.pull(shape.in)


      override def inletOnPush
        (ctx: InletPushedContext[CountedIdentityFlow[Item]])
      : InletPushedContext[CountedIdentityFlow[Item]] =
        ctx
          .push(shape.out, ctx.peek(shape.in))
          .drop(shape.in)
          .withState(mapCount(_ + 1))


      override def receive
        (ctx: ReceiveContext.NotReplied[CountedIdentityFlow[Item]])
      : ReceiveContext[_, CountedIdentityFlow[Item]] =
        ctx.message match {
          case messages.Get =>
            ctx
              .reply(Status.Success(count))
              .handled

          case messages.Reset =>
            ctx
              .reply(Status.Success(count))
              .handled
              .withState(withCount(0))

          case unexpected =>
            ctx.log.warning("Unexpected message: {}", unexpected)
            ctx
        }
    }

    final case class Api(actorRef: ActorRef) {
      import akka.pattern.ask

      def reset()(implicit timeout: Timeout): Future[Long] =
        actorRef.ask(messages.Reset).mapTo[Long]

      def get()(implicit timeout: Timeout): Future[Long] =
        actorRef.ask(messages.Get).mapTo[Long]
    }
  }

  final case class CountedIdentityFlow[Item]() extends Stage[CountedIdentityFlow[Item]] {
    val inlet: Inlet[Item] = Inlet("CountedIdentityFlow.In")
    val outlet: Outlet[Item] = Outlet("CountedIdentityFlow.Out")

    override type Shape = FlowShape[Item, Item]
    override type State = CountedIdentityFlow.State[Item]
    override type MatValue = Future[CountedIdentityFlow.Api]

    override def shape: FlowShape[Item, Item] =
      FlowShape.of(inlet, outlet)

    override def initialStateAndMatValue
      (logic: Stage.RunnerLogic, inheritedAttributes: Attributes)
    : (CountedIdentityFlow.State[Item], Future[CountedIdentityFlow.Api]) = {
      val state = CountedIdentityFlow.State.empty(shape)
      (state, state.apiFuture)
    }
  }
}

final class CountedIdentityFlowTest extends TestBase {
  "CountedIdentityFlow" should "provide runtime access to the stage's state" in
    withMaterializer { implicit mat =>
      futureOk {
        implicit val timeout: Timeout = 1.second
        implicit val ec: ExecutionContext = mat.executionContext

        val ((src, counterApiFut), snk) =
          Source.queue[Int](10, OverflowStrategy.fail)
            .viaMat(Flow.fromGraph(CountedIdentityFlowTest.CountedIdentityFlow().toGraph))(Keep.both)
            .toMat(Sink.queue())(Keep.both)
            .run()

        def pushPull(item: Int): Future[Any] =
          for {
            _ <- src.offer(item).map(_ should be (QueueOfferResult.Enqueued))
            _ <- snk.pull().map(_ should contain (item))
          }
            yield ()

        for {
          counterApi <- counterApiFut
          _ <- counterApi.get().map(_ should be (0))
          _ <- pushPull(1)
          _ <- counterApi.get().map(_ should be (1))
          _ <- pushPull(2)
          _ <- counterApi.get().map(_ should be (2))
          _ <- counterApi.reset().map(_ should be (2))
          _ <- counterApi.reset().map(_ should be (0))
          _ <- pushPull(3)
          _ <- counterApi.get().map(_ should be (1))
          _ <- counterApi.get().map(_ should be (1))
        }
          yield ()
      }
    }
}
