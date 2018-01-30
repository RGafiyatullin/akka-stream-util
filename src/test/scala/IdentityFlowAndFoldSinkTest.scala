import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.stage._
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage.State
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts._

import scala.collection.immutable.Queue
import scala.concurrent.{Future, Promise}

object IdentityFlowAndFoldSinkTest {
  object IdentityStage {
    case class StageState[Item](shape: FlowShape[Item, Item]) extends State[IdentityStage[Item]] {
      override def outletOnPull(ctx: OutletPulledContext[IdentityStage[Item]]): OutletPulledContext[IdentityStage[Item]] = {
        assert(ctx.outlet == shape.out)
        ctx.pull(shape.in)
      }

      override def inletOnPush(ctx: InletPushedContext[IdentityStage[Item]]): InletPushedContext[IdentityStage[Item]] = {
        assert(ctx.inlet == shape.in)
        ctx
          .push(shape.out, ctx.peek(shape.in))
          .drop(shape.in)
      }
    }
  }

  final class IdentityStage[Item] extends Stage[IdentityStage[Item]] {
    override type Shape = FlowShape[Item, Item]
    override type State = IdentityStage.StageState[Item]
    override type MatValue = NotUsed

    val inlet: Inlet[Item] = Inlet("IdentityStage.In")
    val outlet: Outlet[Item] = Outlet("IdentityStage.Out")

    override def shape: FlowShape[Item, Item] =
      FlowShape.of(inlet, outlet)

    override def initialStateAndMatValue
      (logic: Stage.RunnerLogic, inheritedAttributes: Attributes)
    : (State, MatValue) =
      (IdentityStage.StageState(shape), NotUsed)
  }


  object FoldStage {
    final case class State[Acc, Item](
      shape: SinkShape[Item],
      f: (Acc, Item) => Acc,
      acc: Acc,
      resultPromise: Promise[Acc],
      logic: Stage.RunnerLogic)
        extends Stage.State[FoldStage[Acc, Item]]
    {
      override def preStart
        (ctx: PreStartContext[FoldStage[Acc, Item]])
      : PreStartContext[FoldStage[Acc, Item]] =
        ctx.pull(shape.in)

      override def inletOnPush
        (ctx: InletPushedContext[FoldStage[Acc, Item]])
      : InletPushedContext[FoldStage[Acc, Item]] = {
        assert(ctx.inlet == shape.in)
        ctx
          .drop(shape.in)
          .pull(shape.in)
          .withState(copy(acc = f(acc, ctx.peek(shape.in))))
      }

      override def inletOnUpstreamFinish
        (ctx: InletFinishedContext[FoldStage[Acc, Item]])
      : InletFinishedContext[FoldStage[Acc, Item]] = {
        resultPromise.success(acc)
        ctx.completeStage()
      }

      override def inletOnUpstreamFailure
        (ctx: InletFailedContext[FoldStage[Acc, Item]])
      : InletFailedContext[FoldStage[Acc, Item]] = {
        resultPromise.failure(ctx.reason)
        ctx.failStage(ctx.reason)
      }
    }
  }

  final class FoldStage[Acc, Item](zero: Acc, f: (Acc, Item) => Acc) extends Stage[FoldStage[Acc, Item]] {
    override type Shape = SinkShape[Item]
    override type State = FoldStage.State[Acc, Item]
    override type MatValue = Future[Acc]

    val inlet: Inlet[Item] = Inlet("FoldStage.In")

    override def shape: SinkShape[Item] =
      SinkShape.of(inlet)

    override def initialStateAndMatValue
      (logic: Stage.RunnerLogic, inheritedAttributes: Attributes)
    : (FoldStage.State[Acc, Item], Future[Acc]) = {
      val state = FoldStage.State[Acc, Item](shape, f, zero, Promise(), logic)
      (state, state.resultPromise.future)
    }
  }
}

final class IdentityFlowAndFoldSinkTest extends TestBase {
  def checkIdentityStage(identityStage: Flow[Int, Int, _]): Future[Any] =
    withMaterializer { mat =>
      futureOk {
        Source((1 to 10).toList)
          .via(identityStage)
          .toMat(Sink.fold(Queue.empty[Int])(_.enqueue(_)))(Keep.right)
          .run()(mat)
          .map(_ should be (1 to 10))(mat.executionContext)
      }
    }

  def checkFoldStage(foldSink: Sink[Int, Future[Int]]): Future[Any] =
    withMaterializer { mat =>
      futureOk {
        Source((1 to 10).toList)
          .toMat(foldSink)(Keep.right)
          .run()(mat)
          .map(_ should be ((1 to 10).sum))(mat.executionContext)
      }
    }


  "identity-flow stage" should "work if defined as Flow.fromFunction(identity[Int])" in
    checkIdentityStage(Flow.fromFunction(identity[Int]))

  it should "work if defined as Flow.fromGraph(new GraphStage[...]{ ... })" in
    checkIdentityStage(Flow.fromGraph(new GraphStage[FlowShape[Int, Int]] {
      val inlet: Inlet[Int] = Inlet("AdHoc.In")
      val outlet: Outlet[Int] = Outlet("AdHoc.Out")

      override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
        new GraphStageLogic(shape) {
          setHandler(shape.in, new InHandler {
            override def onPush(): Unit =
              push(shape.out, grab(shape.in))
          })
          setHandler(shape.out, new OutHandler {
            override def onPull(): Unit = pull(shape.in)
          })
        }

      override def shape: FlowShape[Int, Int] =
        FlowShape.of(inlet, outlet)

    }))

  it should "work if defined as Flow.fromGraph(new Playground.IdentityStage[Int].toGraph)" in
    checkIdentityStage(Flow.fromGraph(new IdentityFlowAndFoldSinkTest.IdentityStage[Int].toGraph))


  "fold-sink stage" should "work if defined as Sink.fold(0)(_ + _)" in
    checkFoldStage(Sink.fold[Int, Int](0)(_ + _))

  it should "work if defined as Sink.fromGraph(new GraphStage[...]{...}" in
    checkFoldStage(Sink.fromGraph(new GraphStageWithMaterializedValue[SinkShape[Int], Future[Int]] {
      val inlet: Inlet[Int] = Inlet("AdHoc.In")
      override def shape: SinkShape[Int] = SinkShape.of(inlet)

      override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Int]) = {
        val resultPromise: Promise[Int] = Promise()
        val logic = new GraphStageLogic(shape) {
          private var acc: Int = 0



          setHandler(inlet, new InHandler {
            override def onPush(): Unit = {
              acc += grab(inlet)
              pull(inlet)
            }

            override def onUpstreamFailure(ex: Throwable): Unit = {
              resultPromise.failure(ex)
              failStage(ex)
            }

            override def onUpstreamFinish(): Unit = {
              resultPromise.success(acc)
              completeStage()
            }
          })

          override def preStart(): Unit =
            pull(inlet)
        }
        (logic, resultPromise.future)
      }
    }))

  it should "work if defined as Sink.fromGraph(new Playground.FoldStage[Int, Int](0, _ + _).toGraph)" in
    checkFoldStage(Sink.fromGraph(new IdentityFlowAndFoldSinkTest.FoldStage[Int, Int](0, _ + _).toGraph))
}
