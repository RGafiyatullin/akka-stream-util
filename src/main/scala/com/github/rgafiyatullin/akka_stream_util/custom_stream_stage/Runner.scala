package com.github.rgafiyatullin.akka_stream_util.custom_stream_stage

import akka.stream.Attributes
import akka.stream.stage.{GraphStageLogic, GraphStageWithMaterializedValue, InHandler, OutHandler}
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts._

private object Runner {
  final class Logic[Stg <: Stage[Stg]](stage: Stg, inheritedAttributes: Attributes) extends GraphStageLogic(stage.shape) {
    val (initialState, matValue): (Stg#State, Stg#MatValue) = stage.initialStateAndMatValue(this, inheritedAttributes)

    var currentContextInternals: Context.Internals[Stg] =
      Context.Internals.create[Stg](initialState, stage.shape)

    def applyContext[Ctx <: Context[Ctx, Stg]](ctx: Ctx): Unit = {
      currentContextInternals =
        ctx.mapInternals(_
          .mapInlets {
            case (inlet, Context.InletToPull) =>
              pull(inlet)
              Context.InletPulled

            case (_, asIs) =>
              asIs
          }
          .mapOutlets {
            case (outlet, Context.OutletPushed(value)) =>
              push(outlet.as[Any], value)
              Context.OutletFlushed

            case (_, asIs) =>
              asIs
          })
          .internals

        (ctx.stageFailureOption, ctx.stageComplete) match {
          case (None, false) => ()

          case (Some(reason), _) =>
            failStage(reason)

          case (_, true) =>
            completeStage()
        }
      }

    override def preStart(): Unit =
      applyContext(
        currentContextInternals.state.preStart(
          PreStartContext.create(currentContextInternals)))

    override def postStop(): Unit =
      applyContext(
        currentContextInternals.state.postStop(
          PostStopContext.create(currentContextInternals)))

    for (inlet <- stage.shape.inlets)
      setHandler(inlet, new InHandler {
        override def onPush(): Unit =
          applyContext(
            currentContextInternals.state.inletOnPush(
              InletPushedContext.create(inlet, grab(inlet), currentContextInternals)))

        override def onUpstreamFinish(): Unit =
          applyContext(
            currentContextInternals.state.inletOnUpstreamFinish(
              InletFinishedContext.create(inlet, currentContextInternals)))

        override def onUpstreamFailure(reason: Throwable): Unit =
          applyContext(
            currentContextInternals.state.inletOnUpstreamFailure(
              InletFailedContext.create(inlet, reason, currentContextInternals)))
      })

    for (outlet <- stage.shape.outlets)
      setHandler(outlet, new OutHandler {
        override def onDownstreamFinish(): Unit =
          applyContext(
            currentContextInternals.state.outletOnDownstreamFinished(
              OutletFinishedContext.create(outlet, currentContextInternals)))

        override def onPull(): Unit =
          applyContext(
            currentContextInternals.state.outletOnPull(
              OutletPulledContext.create(outlet, currentContextInternals)))
      })


  }
}

private final class Runner[Stg <: Stage[Stg]](stage: Stg) extends GraphStageWithMaterializedValue[Stg#Shape, Stg#MatValue] {
  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Stg#MatValue) = {
    val logic = new Runner.Logic(stage, inheritedAttributes)
    (logic, logic.matValue)
  }

  override def shape: Stg#Shape = stage.shape
}
