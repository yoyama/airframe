/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet

import java.util.concurrent.ConcurrentHashMap

import wvlet.airframe.AirframeMacros._
import wvlet.airframe.surface.Surface
import wvlet.log.LogSupport

import scala.language.experimental.macros
import scala.language.implicitConversions

/**
  *
  */
package object airframe {

  /**
    * The entry point to create a new design beginning from a blanc design
    * <code>
    * import wvlet.airframe._
    *
    * val d = design.bind[X]
    * </code>
    */
  def newDesign: Design = Design.empty

  /**
    * Create an empty design, which sends life cycle logs to debug log level
    */
  def newSilentDesign: Design = newDesign.noLifeCycleLogging

  /**
    * Inject a singleton of A
    *
    * @tparam A
    */
  def bind[A]: A = macro bindImpl[A]
  def bind[A](provider: => A): A = macro bind0Impl[A]
  def bind[A, D1](provider: D1 => A): A = macro bind1Impl[A, D1]
  def bind[A, D1, D2](provider: (D1, D2) => A): A = macro bind2Impl[A, D1, D2]
  def bind[A, D1, D2, D3](provider: (D1, D2, D3) => A): A = macro bind3Impl[A, D1, D2, D3]
  def bind[A, D1, D2, D3, D4](provider: (D1, D2, D3, D4) => A): A = macro bind4Impl[A, D1, D2, D3, D4]
  def bind[A, D1, D2, D3, D4, D5](provider: (D1, D2, D3, D4, D5) => A): A =
    macro bind5Impl[A, D1, D2, D3, D4, D5]

  import scala.language.higherKinds
  def bindFactory[F <: Function1[_, _]]: F = macro bindFactoryImpl[F]
  def bindFactory2[F <: (_, _) => _]: F = macro bindFactory2Impl[F]
  def bindFactory3[F <: (_, _, _) => _]: F = macro bindFactory3Impl[F]
  def bindFactory4[F <: (_, _, _, _) => _]: F = macro bindFactory4Impl[F]
  def bindFactory5[F <: (_, _, _, _, _) => _]: F = macro bindFactory5Impl[F]

  implicit class LifeCycleSupport[A](val dep: A) extends LogSupport {
    @deprecated(message = "Use InitLifeCycle trait or design-time hooks", since = "19.9.0")
    def onInit(body: A => Unit): A = macro addInitLifeCycle[A]
    @deprecated(message = "Use InjectLifeCycle trait or design-time hooks", since = "19.9.0")
    def onInject(body: A => Unit): A = macro addInjectLifeCycle[A]
    @deprecated(message = "Use StartLifeCycle trait or design-time hooks", since = "19.9.0")
    def onStart(body: A => Unit): A = macro addStartLifeCycle[A]
    @deprecated(message = "Use BeforeShutdownLifeCycle trait or design-time hooks", since = "19.9.0")
    def beforeShutdown(body: A => Unit): A = macro addPreShutdownLifeCycle[A]
    @deprecated(message = "Use ShutdownLifeCycle trait or design-time hooks", since = "19.9.0")
    def onShutdown(body: A => Unit): A = macro addShutdownLifeCycle[A]
  }

  // For internal use to hold caches of factories of trait with a session
  def registerTraitFactory[A]: Surface = macro registerTraitFactoryImpl[A]

  import scala.jdk.CollectionConverters._
  val traitFactoryCache = new ConcurrentHashMap[Surface, Session => Any].asScala
  def getOrElseUpdateTraitFactoryCache(s: Surface, factory: Session => Any): Session => Any = {
    traitFactoryCache.getOrElseUpdate(s, factory)
  }

  //import wvlet.obj.tag._
  // Automatically add tag
  //implicit def toTaggedType[A, Tag](obj: A): A @@ Tag = obj.taggedWith[Tag]
}
