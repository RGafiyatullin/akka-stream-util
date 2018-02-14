package com.github.rgafiyatullin.akka_stream_util.custom_stream_stage

import akka.stream.{Attributes, Materializer}
import akka.stream.stage.{GraphStageWithMaterializedValue, StageLogging, TimerGraphStageLogic}
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts._

object Stage {
  type Shape = akka.stream.Shape

  trait State[Stg <: Stage[Stg]] {
    def preStart(ctx: PreStartContext[Stg]): PreStartContext[Stg] = ctx
    def postStop(ctx: PostStopContext[Stg]): PostStopContext[Stg] = ctx

    def inletOnPush(ctx: InletPushedContext[Stg]): InletPushedContext[Stg] = ctx
    def outletOnPull(ctx: OutletPulledContext[Stg]): OutletPulledContext[Stg] = ctx

    def inletOnUpstreamFinish(ctx: InletFinishedContext[Stg]): InletFinishedContext[Stg] =
      ctx.completeStage()

    def inletOnUpstreamFailure(ctx: InletFailedContext[Stg]): InletFailedContext[Stg] =
      ctx.failStage(ctx.reason)

    def outletOnDownstreamFinish(ctx: OutletFinishedContext[Stg]): OutletFinishedContext[Stg] =
      ctx.completeStage()

    def receiveEnabled: Boolean = false
    def receive(ctx: ReceiveContext.NotReplied[Stg]): ReceiveContext[Stg] =
      ctx.handleWith(Map.empty)
  }

  trait Runner[Stg <: Stage[Stg]] extends GraphStageWithMaterializedValue[Stg#Shape, Stg#MatValue]
  trait RunnerLogic
    extends TimerGraphStageLogic
      with StageLogging
  {
    override def materializer: Materializer = super.materializer
  }
}

trait Stage[Self <: Stage[Self]] {
  this: Self =>

  type Shape <: Stage.Shape
  type State <: Stage.State[Self]
  type MatValue

  def shape: Shape
  def initialStateAndMatValue(logic: Stage.RunnerLogic, inheritedAttributes: Attributes): (State, MatValue)

  def toGraph: GraphStageWithMaterializedValue[Self#Shape, Self#MatValue] =
    new RunnerImpl[Self](this)
}
