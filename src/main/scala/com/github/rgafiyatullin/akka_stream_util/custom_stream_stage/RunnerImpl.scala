package com.github.rgafiyatullin.akka_stream_util.custom_stream_stage

import akka.stream.Attributes
import akka.stream.stage._
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts._

import scala.util.Try
import scala.util.control.NonFatal

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
          applyContext { internals =>
            val ctxIn = ReceiveContext.create(sender, message, internals)
            val ctxOut = internals.state.receive(ctxIn)

            if (!ctxOut.isHandled)
              log.warning("Unhandled inbound message {} from {}", message, sender)

            ctxOut
          }
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
            case (outlet, Context.OutletToPush(value)) =>
              push(outlet.as[Any], value)
              Context.OutletFlushed

            case (outlet, Context.OutletToFail(reason)) =>
              fail(outlet, reason)
              Context.OutletFailed(reason)

            case (outlet, Context.OutletToComplete) =>
              complete(outlet)
              Context.OutletCompleted

            case (outlet, Context.OutletToPushThenComplete(value)) =>
              push(outlet.as[Any], value)
              complete(outlet)
              Context.OutletCompleted

            case (outlet, Context.OutletToPushThenFail(value, reason)) =>
              push(outlet.as[Any], value)
              fail(outlet, reason)
              Context.OutletFailed(reason)

            case (_, asIs) =>
              asIs
          }

      (currentContextInternals.stageFailureOption,
        currentContextInternals.stageComplete)
      match {
        case (None, false) => ()

        case (Some(reason), _) =>
          currentContextInternals =
            Try(
              currentContextInternals.state.onStageFailure(
                StageFailedContext.create(currentContextInternals, reason))
                .internals)
              .getOrElse(currentContextInternals)
          failStage(reason)

        case (_, true) =>
          completeStage()
      }
    }


    def applyContext[Ctx <: Context[Ctx, Stg]](process: Context.Internals[Stg] => Ctx): Unit = {
      val ctxInternalsTried = Try {
        val ctx = process(currentContextInternals)
        ctx.onApply()
        ctx.internals
      } recover {
        case NonFatal(reason) =>
          currentContextInternals.failStage(reason)
      }

      applyContextInternals(ctxInternalsTried.get)
    }

    override def preStart(): Unit = {
      if (currentContextInternals.state.receiveEnabled)
        initializeStageActor()

      applyContext(internals =>
        internals.state.preStart(
          PreStartContext.create(internals)))
    }



    override def postStop(): Unit =
      applyContext(internals =>
        internals.state.postStop(
          PostStopContext.create(internals)))

    for (inlet <- stage.shape.inlets)
      setHandler(inlet, new InHandler {
        override def onPush(): Unit =
          applyContext(internals =>
            internals.state.inletOnPush(
              InletPushedContext.create(inlet, grab(inlet), internals)))

        override def onUpstreamFinish(): Unit =
          applyContext(internals =>
            internals.state.inletOnUpstreamFinish(
              InletFinishedContext.create(inlet, internals)))

        override def onUpstreamFailure(reason: Throwable): Unit =
          applyContext(internals =>
            internals.state.inletOnUpstreamFailure(
              InletFailedContext.create(inlet, reason, internals)))
      })

    for (outlet <- stage.shape.outlets)
      setHandler(outlet, new OutHandler {
        override def onDownstreamFinish(): Unit =
          applyContext(internals =>
            internals.state.outletOnDownstreamFinish(
              OutletFinishedContext.create(outlet, internals)))

        override def onPull(): Unit =
          applyContext(internals =>
            internals.state.outletOnPull(
              OutletPulledContext.create(outlet, internals)))
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
