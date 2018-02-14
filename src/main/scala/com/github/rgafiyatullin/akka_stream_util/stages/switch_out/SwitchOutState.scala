package com.github.rgafiyatullin.akka_stream_util.stages.switch_out

import akka.Done
import akka.stream.{Inlet, Outlet}
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts._
import com.github.rgafiyatullin.akka_stream_util.stages.{SwitchOut, Switch}

import scala.concurrent.Promise

sealed trait SwitchOutState[Key, Item] extends Stage.State[SwitchOut[Key, Item]] {
  type St = SwitchOut[Key, Item]
  type Sh = SwitchOutShape[Key, Item]

  def shape: Sh
  def activeKey: Key
  def activeOutlet: Outlet[Item] = shape.out(activeKey)
  def outlet(key: Key): Outlet[Item] = shape.out(key)
  def inlet: Inlet[Item] = shape.in

}

object SwitchOutState {
  def create[Key, Item]
    (initialActiveKey: Key,
     shape: SwitchOutShape[Key, Item],
     apiPromise: Promise[Switch[Key]])
  : SwitchOutState[Key, Item] =
    Init(initialActiveKey, shape, apiPromise)


  final case class Init[Key, Item]
    (activeKey: Key,
     shape: SwitchOutShape[Key, Item],
     apiPromise: Promise[Switch[Key]])
    extends SwitchOutState[Key, Item]
  {
    def initialized: SwitchOutState[Key, Item] =
      Normal(activeKey, shape)


    override def receiveEnabled: Boolean = true

    override def postStop(ctx: PostStopContext[SwitchOut[Key, Item]]): PostStopContext[SwitchOut[Key, Item]] = {
      apiPromise.tryFailure(new UninitializedError())
      ctx
    }

    override def preStart(ctx: PreStartContext[SwitchOut[Key, Item]]): PreStartContext[SwitchOut[Key, Item]] = {
      val api = Switch[Key](ctx.stageActorRef)
      apiPromise.success(api)
      ctx.withState(initialized)
    }
  }


  final case class Normal[Key, Item]
    (activeKey: Key,
     shape: SwitchOutShape[Key, Item])
    extends SwitchOutState[Key, Item]
  {
    def setActiveKey(key: Key): Normal[Key, Item] =
      copy(activeKey = key)

    def saveState[Ctx <: Context[Ctx, St]](ctx: Ctx): Ctx =
      ctx.withState(this)

    def pipeItem[Ctx <: Context[Ctx, St]](ctx: Ctx): Ctx =
      saveState(
        ctx
          .push(activeOutlet, ctx.peek(inlet))
          .drop(inlet))

    def pull[Ctx <: Context[Ctx, St]](ctx: Ctx): Ctx =
      saveState(
        ctx.pull(inlet))


    def switchKey[Ctx <: Context[Ctx, St]](ctx: Ctx, key: Key): Ctx =
      (ctx.isCompleted(outlet(key)),
        ctx.isAvailable(outlet(key)),
        ctx.isAvailable(inlet),
        ctx.isPulled(inlet)) match
      {
        case (true, _, _, _) =>
          setActiveKey(key).saveState(ctx.completeStage())

        case (false, true, true, _) =>
          setActiveKey(key).pipeItem(ctx)

        case (false, true, false, false) =>
          setActiveKey(key).pull(ctx)

        case (false, _, _, _) =>
          setActiveKey(key).saveState(ctx)
      }

    override def receive(ctx: ReceiveContext.NotReplied[SwitchOut[Key, Item]]): ReceiveContext[SwitchOut[Key, Item]] =
      ctx.handleWith {
        case Switch.To(key) if !shape.outletMap.contains(key.asInstanceOf[Key]) =>
          ctx.replyFailure(new NoSuchElementException("Invalid outlet-key: %s".format(key)))

        case Switch.To(key) =>
          switchKey(ctx, key.asInstanceOf[Key]).replySuccess(Done)
      }

    override def outletOnPull(ctx: OutletPulledContext[SwitchOut[Key, Item]]): OutletPulledContext[SwitchOut[Key, Item]] =
      (ctx.outlet == activeOutlet, ctx.isPulled(inlet)) match {
        case (true, false) =>
          pull(ctx)

        case (_, _) =>
          saveState(ctx)
      }

    override def inletOnPush(ctx: InletPushedContext[SwitchOut[Key, Item]]): InletPushedContext[SwitchOut[Key, Item]] =
      if (ctx.isAvailable(activeOutlet))
        pipeItem(ctx)
      else
        saveState(ctx)
  }
}
