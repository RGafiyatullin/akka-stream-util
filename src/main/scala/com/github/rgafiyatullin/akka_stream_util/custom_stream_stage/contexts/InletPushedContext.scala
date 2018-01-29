package com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts

import akka.NotUsed
import akka.stream.Inlet
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage

object InletPushedContext {
  def create[Stg <: Stage[Stg]](inlet: Inlet[_], value: Any, internals: Context.Internals[Stg]): InletPushedContext[Stg] =
    InletPushedContext(inlet, internals.mapFoldInlet(inlet, {
      case Context.InletPulled =>
        (NotUsed, Context.InletAvailalbe(value))
    })._2)
}

final case class InletPushedContext[Stg <: Stage[Stg]](inlet: Inlet[_], internals: Context.Internals[Stg])
  extends Context[InletPushedContext [Stg], Stg]
{
  type Self = InletPushedContext[Stg]

  def port: Inlet[_] = inlet

  override def withInternals(i: Context.Internals[Stg]): Self =
    copy(internals = i)
}
