package com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts

import akka.NotUsed
import akka.stream.Inlet
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage

object InletFailedContext {
  def create[Stg <: Stage[Stg]]
    (inlet: Inlet[_],
     reason: Throwable,
     internals: Context.Internals[Stg])
  : InletFailedContext[Stg] =
    InletFailedContext(
      internals.mapFoldInlet(inlet, {
        case _ =>
          (NotUsed, Context.InletFailed(reason))
      })._2,
      reason, inlet)
}

final case class InletFailedContext[Stg <: Stage[Stg]] (
  internals: Context.Internals[Stg],
  reason: Throwable,
  inlet: Inlet[_])
    extends Context[InletFailedContext[Stg], Stg]
{
  type Self = InletFailedContext[Stg]

  override def withInternals(i: Context.Internals[Stg]): Self =
    copy(internals = i)
}
