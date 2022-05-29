package zhttp.api

import zhttp.http.{Http, HttpApp, Request, Response}
import zio.schema.Schema
import zio.schema.codec.JsonCodec
import zio.{Chunk, UIO, ZIO}

private[api] object ServerInterpreter {

  def handlerToHttpApp[R, E, Params, Input, Output](
    handler: Handler[R, E, Params, Input, Output],
  ): HttpApp[R, E] = {
    val requestCodec: RequestCodec[Params]                 = handler.api.requestCodec
    val outputEncoder: Output => Chunk[Byte]               = JsonCodec.encode(handler.api.outputSchema)
    val inputDecoder: Chunk[Byte] => Either[String, Input] = JsonCodec.decode(handler.api.inputSchema)

    // TODO:
    //  Use request.unsafeEncode
    //  First, parse the url, then parse the remaining query string if there is one
    def withInput(request: Request)(process: Input => ZIO[R, E, Response]): ZIO[R, E, Response] = {
      if (handler.api.inputSchema == Schema[Unit]) {
        process(().asInstanceOf[Input])
      } else {
        request.body.flatMap { string =>
          inputDecoder(string) match {
            case Left(err)    => UIO(Response.text(s"Invalid input: $err"))
            case Right(value) => process(value)
          }
        }.catchAll { err =>
          UIO(Response.text(s"Error parsing request body: $err"))
        }
      }
    }

    Http.collectZIO {
      case req @ requestCodec(result) if req.method == handler.api.method =>
        withInput(req) { input =>
          handler
            .handle((result, input))
            .map { apiResponse =>
              if (handler.api.outputSchema == Schema[Unit])
                Response.ok
              else
                Response
                  .json(new String(outputEncoder(apiResponse.value).toArray))
                  .addHeaders(apiResponse.headers)
                  .setStatus(apiResponse.status)
            }
        }
    }
  }

}