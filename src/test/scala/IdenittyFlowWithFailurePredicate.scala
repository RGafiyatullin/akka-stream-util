
import akka.NotUsed
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts.{InletPushedContext, OutletPulledContext}

import scala.collection.immutable.Queue
import scala.concurrent.ExecutionContext

object IdenittyFlowWithFailurePredicate {
  object IFWFPStage {
    object State {
      def create[Item](shape: FlowShape[Item, Item], itemToFailureOption: Item => Option[Throwable]): State[Item] =
        StateNormal(shape, itemToFailureOption)
    }

    sealed trait State[Item] extends Stage.State[IFWFPStage[Item]]

    final case class StateFailed[Item](inlet: Inlet[Item], reason: Throwable) extends State[Item] {
      override def inletOnPush
        (ctx: InletPushedContext[IFWFPStage[Item]])
      : InletPushedContext[IFWFPStage[Item]] =
        ctx.drop(inlet).pull(inlet)
    }

    final case class StateNormal[Item](shape: FlowShape[Item, Item], itemToFailureOption: Item => Option[Throwable]) extends State[Item] {
      override def outletOnPull
        (ctx: OutletPulledContext[IFWFPStage[Item]])
      : OutletPulledContext[IFWFPStage[Item]] =
        ctx.pull(shape.in)

      override def inletOnPush
        (ctx: InletPushedContext[IFWFPStage[Item]])
      : InletPushedContext[IFWFPStage[Item]] = {
        val value = ctx.peek(shape.in)
        val ctxDropped = ctx.drop(shape.in)

        itemToFailureOption(value) match {
          case None =>
            ctxDropped.push(shape.out, value)

          case Some(reason) =>
            ctxDropped
              .fail(shape.out, reason)
              .pull(shape.in)
              .withState(StateFailed(shape.in, reason))
        }
      }
    }
  }

  final case class IFWFPStage[Item](
    itemToFailureOption: Item => Option[Throwable])
      extends Stage[IFWFPStage[Item]]
  {
    override type Shape = FlowShape[Item, Item]
    override type State = IFWFPStage.State[Item]
    override type MatValue = NotUsed

    val inlet: Inlet[Item] = Inlet("In")
    val outlet: Outlet[Item] = Outlet("Out")

    override def shape: FlowShape[Item, Item] =
      FlowShape.of(inlet, outlet)

    override def initialStateAndMatValue
      (logic: Stage.RunnerLogic,
       inheritedAttributes: Attributes)
    : (IFWFPStage.State[Item], NotUsed) =
      (IFWFPStage.State.create(shape, itemToFailureOption), NotUsed)
  }
}

final class IdenittyFlowWithFailurePredicate extends TestBase {
  "IFWFP" should "work as identity flow when `f(item) = None`" in
    unit(withMaterializer { mat =>
      futureOk {
        Source((1 to 10).toList)
          .via(IdenittyFlowWithFailurePredicate.IFWFPStage[Int](_ => None).toGraph)
          .toMat(Sink.fold(Queue.empty[Int])(_.enqueue(_)))(Keep.right)
          .run()(mat)
          .map(_ should be ((1 to 10).toList))(mat.executionContext)
      }
    })

  it should "pass all elements before failure and then fail" in
    unit(withMaterializer { mat =>
      futureOk {
        implicit val ec: ExecutionContext = mat.executionContext
        val snkQ =
          Source((1 to 10).toList)
            .via(IdenittyFlowWithFailurePredicate.IFWFPStage[Int](Map(5 -> new Exception("ITSAFIVE!!!") ).get).toGraph)
            .toMat(Sink.queue[Int]())(Keep.right)
            .run()(mat)

        for {
          _ <- snkQ.pull().map(_ should contain (1))
          _ <- snkQ.pull().map(_ should contain (2))
          _ <- snkQ.pull().map(_ should contain (3))
          _ <- snkQ.pull().map(_ should contain (4))
          _ <- snkQ.pull().failed.map(_ shouldBe an[Exception])
        }
          yield ()
      }
    })
}
