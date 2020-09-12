package component

import monix.reactive._
import monix.reactive.subjects._
import monix.execution.Scheduler.Implicits.global
import logic.Dispatcher
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.core.facade.{Hooks, ReactElement}
import slinky.web.html._
import slinky.core.Component

object Connect {

  def apply[Model, R](observable: Observable[Model])(
    component: Model => ReactElement
  ): ReactElement = {

    val mutableVar = Var(Option.empty[Model])
    val subscription = observable.subscribe(mutableVar := Some(_))

    val wrapper = FunctionalComponent[Unit] { _ =>
      val (state, setState) = Hooks.useState(mutableVar())
      def subscribe(): () => Unit = {
        mutableVar.tail.foreach(setState)
        () => subscription.cancel()
      }
      Hooks.useEffect(subscribe, List(true))
      state.fold[ReactElement](span())(component)
    }

    wrapper()
  }
}
