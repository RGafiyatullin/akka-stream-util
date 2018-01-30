package com.github.rgafiyatullin.akka_stream_util.custom_stream_stage

import akka.stream.{Attributes, Graph}
import akka.stream.stage.{GraphStageLogic, GraphStageWithMaterializedValue}
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

    def outletOnDownstreamFinished(ctx: OutletFinishedContext[Stg]): OutletFinishedContext[Stg] =
      ctx.completeStage()
  }
}

trait Stage[Self <: Stage[Self]] {
  type Shape <: Stage.Shape
  type State <: Stage.State[Self]
  type MatValue

  def shape: Shape
  def initialStateAndMatValue(logic: GraphStageLogic, inheritedAttributes: Attributes): (State, MatValue)

  def toGraph: GraphStageWithMaterializedValue[Self#Shape, Self#MatValue] =
    new Runner[Self](this.asInstanceOf[Self])
}
