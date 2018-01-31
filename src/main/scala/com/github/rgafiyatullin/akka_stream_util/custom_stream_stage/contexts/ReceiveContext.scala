package com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts

import akka.actor.{ActorRef, Status}
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage

object ReceiveContext {
  def create[Stg <: Stage[Stg]]
    (sender: ActorRef, message: Any,
     internals: Context.Internals[Stg])
  : NotReplied[Stg] =
    NotReplied(sender, message, isHandled = false, internals)

  type Receive[Stg <: Stage[Stg]] =
    Stg#State => NotReplied[Stg] => ReceiveContext[_, Stg]

  final case class NotReplied[Stg <: Stage[Stg]](
    sender: ActorRef,
    message: Any,
    isHandled: Boolean,
    internals: Context.Internals[Stg])
      extends ReceiveContext[NotReplied[Stg], Stg]
  {
    override def withInternals(i: Context.Internals[Stg]): NotReplied[Stg] =
      copy(internals = i)

    def reply(response: Status.Status): Replied[Stg] =
      Replied(sender, response, message, isHandled, internals)

    override def handled: NotReplied[Stg] =
      copy(isHandled = true)

    override def unhandled: NotReplied[Stg] =
      copy(isHandled = false)
  }

  final case class Replied[Stg <: Stage[Stg]](
    replyTo: ActorRef,
    replyWith: Status.Status,
    requestMessage: Any,
    isHandled: Boolean,
    internals: Context.Internals[Stg])
      extends ReceiveContext[Replied[Stg], Stg]
  {
    override def onApply(): Unit =
      replyTo ! replyWith

    override def withInternals(i: Context.Internals[Stg]): Replied[Stg] =
      copy(internals = i)

    def unreply: NotReplied[Stg] =
      NotReplied(replyTo, requestMessage, isHandled, internals)

    override def handled: Replied[Stg] =
      copy(isHandled = true)

    override def unhandled: Replied[Stg] =
      copy(isHandled = false)
  }
}

sealed trait ReceiveContext[Concrete <: ReceiveContext[Concrete, Stg], Stg <: Stage[Stg]]
  extends Context[Concrete, Stg]
{
  def handled: Concrete
  def unhandled: Concrete
  def isHandled: Boolean
}

