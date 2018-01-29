import akka.NotUsed
import akka.event.Logging
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage.State
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts.{InletPushedContext, OutletPulledContext}

import scala.collection.immutable.Queue
import scala.concurrent.Future

object Playground {
  object IdentityStage {
    trait StageState[Item] extends State[IdentityStage[Item]]
    case class Initial[Item](shape: FlowShape[Item, Item]) extends StageState[Item] {
      override def outletOnPull(ctx: OutletPulledContext[IdentityStage[Item]]): OutletPulledContext[IdentityStage[Item]] =
        ctx.pull(shape.in)

      override def inletOnPush(ctx: InletPushedContext[IdentityStage[Item]]): InletPushedContext[IdentityStage[Item]] =
        ctx
          .push(shape.out, ctx.peek(shape.in))
          .drop(shape.in)
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
      (logic: GraphStageLogic, inheritedAttributes: Attributes)
    : (State, MatValue) =
      (IdentityStage.Initial(shape), NotUsed)
  }
}

final class Playground extends TestBase {
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

  "identity stage" should "work if defined as Flow.fromFunction(identity[Int])" in
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
    checkIdentityStage(Flow.fromGraph(new Playground.IdentityStage[Int].toGraph))
}
