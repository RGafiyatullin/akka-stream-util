package com.github.rgafiyatullin.akka_stream_util.custom_stream_stage

import akka.stream.Attributes
import akka.stream.stage._
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts._

private object RunnerImpl {
  final class Logic[Stg <: Stage[Stg]](stage: Stg, inheritedAttributes: Attributes)
    extends TimerGraphStageLogic(stage.shape)
      with Stage.RunnerLogic
  {
    val (initialState, matValue): (Stg#State, Stg#MatValue) =
      stage.initialStateAndMatValue(this, inheritedAttributes)

    var currentContextInternals: Context.Internals[Stg] =
      Context.Internals.create[Stg](initialState, stage.shape, this)

    def initializeStageActor(): Unit = {
      val _ = getStageActor {
        case (sender, message) =>
          val ctxIn = ReceiveContext.create(sender, message, currentContextInternals)
          val ctxOut = currentContextInternals.state.receive(ctxIn)

          if (!ctxOut.isHandled)
            log.warning("Unhandled inbound message {} from {}", message, sender)

          applyContext(ctxOut)
      }
    }

    def applyContextInternals(nextContextInternals: Context.Internals[Stg]): Unit = {
      currentContextInternals =
        nextContextInternals
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
          }

      (currentContextInternals.stageFailureOption,
        currentContextInternals.stageComplete)
      match {
        case (None, false) => ()

        case (Some(reason), _) =>
          failStage(reason)

        case (_, true) =>
          completeStage()
      }
    }


    def applyContext(ctx: Context[_, Stg]): Unit = {
      ctx.onApply()
      applyContextInternals(ctx.internals)
    }

    override def preStart(): Unit = {
      if (currentContextInternals.state.receiveEnabled)
        initializeStageActor()

      applyContext(
        currentContextInternals.state.preStart(
          PreStartContext.create(currentContextInternals)))
    }

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

private final class RunnerImpl[Stg <: Stage[Stg]](stage: Stg) extends Stage.Runner[Stg] {
  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Stg#MatValue) = {
    val logic = new RunnerImpl.Logic(stage, inheritedAttributes)
    (logic, logic.matValue)
  }

  override def shape: Stg#Shape = stage.shape
}
