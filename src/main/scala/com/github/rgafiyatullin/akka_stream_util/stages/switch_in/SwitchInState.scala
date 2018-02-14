package com.github.rgafiyatullin.akka_stream_util.stages.switch_in

import akka.Done
import akka.stream.{Inlet, Outlet}
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts._
import com.github.rgafiyatullin.akka_stream_util.stages.{SwitchOut, SwitchIn, Switch}

import scala.concurrent.Promise

sealed trait SwitchInState[Key, Item] extends Stage.State[SwitchIn[Key, Item]] {
  type St = SwitchIn[Key, Item]
  type Sh = SwitchInShape[Key, Item]

  def shape: Sh
  def activeKey: Key
  def activeInlet: Inlet[Item] = shape.in(activeKey)
  def inlet(key: Key): Inlet[Item] = shape.in(key)
  def outlet: Outlet[Item] = shape.out

}

object SwitchInState {
  def create[Key, Item](shape: SwitchInShape[Key, Item], initialActiveKey: Key, apiPromise: Promise[Switch[Key]]): SwitchInState[Key, Item] =
    Init(initialActiveKey, shape, apiPromise)

  final case class Init[Key, Item]
    (activeKey: Key,
     shape: SwitchInShape[Key, Item],
     apiPromise: Promise[Switch[Key]])
    extends SwitchInState[Key, Item]
  {
    override def receiveEnabled: Boolean = true

    override def postStop(ctx: PostStopContext[SwitchIn[Key, Item]]): PostStopContext[SwitchIn[Key, Item]] = {
      apiPromise.tryFailure(new UninitializedError())
      ctx
    }

    override def preStart(ctx: PreStartContext[SwitchIn[Key, Item]]): PreStartContext[SwitchIn[Key, Item]] = {
      val api = Switch[Key](ctx.stageActorRef)
      apiPromise.success(api)
      ctx.withState(initialized)
    }

    def initialized: SwitchInState[Key, Item] =
      Normal(activeKey, shape)
  }

  final case class Normal[Key, Item]
    (activeKey: Key,
     shape: SwitchInShape[Key, Item])
    extends SwitchInState[Key, Item]
  {
    def setActiveKey(key: Key): Normal[Key, Item] =
      copy(activeKey = key)

    def saveState[Ctx <: Context[Ctx, St]](ctx: Ctx): Ctx =
      ctx.withState(this)

    def pipeItem[Ctx <: Context[Ctx, St]](ctx: Ctx): Ctx =
      saveState(
        ctx
          .push(outlet, ctx.peek(activeInlet))
          .drop(activeInlet))

    def pull[Ctx <: Context[Ctx, St]](ctx: Ctx): Ctx =
      saveState(
        ctx.pull(activeInlet))


    def switchActiveKey[Ctx <: Context[Ctx, St]](ctx: Ctx, key: Key): Ctx =
      (ctx.isCompleted(inlet(key)),
        ctx.failureOption(inlet(key)),
        ctx.isAvailable(inlet(key)),
        ctx.isAvailable(outlet),
        ctx.isPulled(inlet(key))) match
      {
        case (false, Some(reason), _, _, _) =>
          ctx.failStage(reason)

        case (true, _, _, _, _) =>
          ctx.completeStage()

        case (false, None, true, true, _) =>
          setActiveKey(key).pipeItem(ctx)

        case (false, None, true, false, _) =>
          setActiveKey(key).saveState(ctx)

        case (false, None, false, true, false) =>
          setActiveKey(key).pull(ctx)

        case (false, None, false, _, _) =>
          setActiveKey(key).saveState(ctx)
      }

    override def receive(ctx: ReceiveContext.NotReplied[SwitchIn[Key, Item]]): ReceiveContext[SwitchIn[Key, Item]] =
      ctx.handleWith {
        case Switch.To(key) if !shape.inletMap.contains(key.asInstanceOf[Key]) =>
          ctx.replyFailure(new NoSuchElementException("Invalid inlet-key: %s".format(key)))

        case Switch.To(key) =>
          switchActiveKey(ctx, key.asInstanceOf[Key]).replySuccess(Done)
      }

    override def outletOnPull(ctx: OutletPulledContext[SwitchIn[Key, Item]]): OutletPulledContext[SwitchIn[Key, Item]] =
      (ctx.isAvailable(activeInlet), ctx.isPulled(activeInlet)) match {
        case (true, _) =>
          pipeItem(ctx)

        case (false, false) =>
          pull(ctx)

        case (false, true) =>
          ctx
      }


    override def inletOnPush(ctx: InletPushedContext[SwitchIn[Key, Item]]): InletPushedContext[SwitchIn[Key, Item]] =
      (ctx.inlet == activeInlet, ctx.isAvailable(outlet)) match {
        case (true, true) =>
          pipeItem(ctx)

        case (_, _) =>
          ctx
      }

    override def outletOnDownstreamFinish
      (ctx: OutletFinishedContext[SwitchIn[Key, Item]])
    : OutletFinishedContext[SwitchIn[Key, Item]] =
      ctx.completeStage()

    override def inletOnUpstreamFinish(ctx: InletFinishedContext[SwitchIn[Key, Item]]): InletFinishedContext[SwitchIn[Key, Item]] =
      if (ctx.inlet == activeInlet) ctx.completeStage()
      else ctx

    override def inletOnUpstreamFailure(ctx: InletFailedContext[SwitchIn[Key, Item]]): InletFailedContext[SwitchIn[Key, Item]] =
      if (ctx.inlet == activeInlet) ctx.failStage(ctx.reason)
      else ctx
  }

}

