import diode.FastEq
import slinky.core.facade.ReactElement
import slinky.core.KeyAddingStage
import slinky.core.FunctionalComponent
import slinky.core.facade.Hooks
import diode.Dispatcher

object Connect {
  def apply[Model, R](circuit: AppCircuit, zoom: RootModel => Model)(component: (Model, Dispatcher) => R)(
    implicit feq: FastEq[_ >: Model], toReact: R => ReactElement
  ): KeyAddingStage = {
    val wrapper = FunctionalComponent[Unit] { _ =>
      val modelR = circuit.zoom(zoom)
      val (state, setState) = Hooks.useState(modelR.value)
      def subscribe() = circuit.subscribe(modelR)(m => setState(m.value))
      Hooks.useEffect(subscribe)
      component(modelR.value, circuit)
    }
    wrapper()
  }
}