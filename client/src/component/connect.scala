package component

import rx.{Rx, Obs}
import logic.Dispatcher
import slinky.core.{FunctionalComponent, KeyAddingStage}
import slinky.core.facade.{Hooks, ReactElement}

object Connect {
  def apply[Model, R](observed: Rx[Model])(
    component: Model => ReactElement
  ): KeyAddingStage = {
    val wrapper = FunctionalComponent[Unit] { _ =>
      val (state, setState) = Hooks.useState(observed.now)
      def subscribe(): Obs = observed.trigger(value => setState(value))(rx.Ctx.Owner.Unsafe)
      Hooks.useEffect { () => 
        val obs = subscribe() 
        () => obs.kill()
      }
      component(state)
    }
    wrapper()
  }
}
