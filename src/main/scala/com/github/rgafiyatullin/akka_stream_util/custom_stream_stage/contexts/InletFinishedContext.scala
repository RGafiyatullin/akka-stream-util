package com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.contexts

import akka.stream.Inlet
import com.github.rgafiyatullin.akka_stream_util.custom_stream_stage.Stage

object InletFinishedContext {
  def create[Stg <: Stage[Stg]](inlet: Inlet[_], internals: Context.Internals[Stg]) =
    InletFinishedContext(internals, inlet)

}

final case class InletFinishedContext[Stg <: Stage[Stg]] (
  internals: Context.Internals[Stg],
  inlet: Inlet[_])
    extends Context[InletFinishedContext[Stg], Stg]
{
  type Self = InletFinishedContext[Stg]

  override def withInternals(i: Context.Internals[Stg]): Self =
    copy(internals = i)
}
