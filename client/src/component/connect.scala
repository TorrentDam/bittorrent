package component

import frp.Observable
import logic.Dispatcher
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.core.facade.{Hooks, ReactElement}

object Connect {
  def apply[Model, R](observed: Observable[Model], dispatcher: Dispatcher)(
    component: (Model, Dispatcher) => ReactElement
  ): KeyAddingStage = {
    val wrapper = FunctionalComponent[Unit] { _ =>
      val (state, setState) = Hooks.useState(observed.value)
      def subscribe(): Observable.Unsubscribe = observed.subscribe(value => setState(value))
      Hooks.useEffect(subscribe)
      component(state, dispatcher)
    }
    wrapper()
  }
}
