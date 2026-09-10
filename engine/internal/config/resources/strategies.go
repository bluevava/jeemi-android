package resources

import "jeemi-android/engine/internal/config/compose"

func composeStrategyReplace() compose.Strategy { return compose.StrategyReplace }
func composeStrategyPrepend() compose.Strategy { return compose.StrategyPrepend }
func composeStrategyAppendBeforeTerminal() compose.Strategy {
	return compose.StrategyAppendBeforeTerminal
}
