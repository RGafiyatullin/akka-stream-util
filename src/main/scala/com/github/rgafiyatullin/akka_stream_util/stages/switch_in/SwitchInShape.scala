package com.github.rgafiyatullin.akka_stream_util.stages.switch_in

import akka.stream.{Inlet, Outlet, Shape}

import scala.collection.immutable

final case class SwitchInShape[Key, Item]
  (inletMap: Map[Key, Inlet[Item]],
   out: Outlet[Item])
  extends Shape
{
  def in(key: Key): Inlet[Item] =
    inletMap(key)

  override def inlets: immutable.Seq[Inlet[_]] =
    inletMap.values.toList

  override def outlets: immutable.Seq[Outlet[_]] =
    List(out)

  override def deepCopy(): Shape =
    SwitchInShape(
      inletMap.map { case (k,v) => k -> v.carbonCopy() },
      out.carbonCopy())
}
