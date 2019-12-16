import slinky.core.facade.ReactElement
import slinky.core.KeyAddingStage
import slinky.core.FunctionalComponent
import slinky.core.facade.Hooks

object Connect {
  def apply[Model, R](observed: Observed[Model], dispatcher: Dispatcher)(
    component: (Model, Dispatcher) => ReactElement
  ): KeyAddingStage = {
    val wrapper = FunctionalComponent[Unit] { _ =>
      val (state, setState) = Hooks.useState(observed.value)
      def subscribe(): Observed.Unsubscribe = observed.subscribe(value => setState(value))
      Hooks.useEffect(subscribe)
      component(state, dispatcher)
    }
    wrapper()
  }
}
