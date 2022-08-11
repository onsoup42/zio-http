package zhttp

import zio.Scope

package object internal {
  type HttpEnv = Scope with DynamicServer
}
