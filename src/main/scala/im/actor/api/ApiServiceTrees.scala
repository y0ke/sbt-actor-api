package im.actor.api

import treehugger.forest._, definitions._
import treehuggerDSL._

trait ApiServiceTrees extends TreeHelpers {
  lazy val ScalazEitherType = definitions.getClass("\\/")

  def apiServiceTree = {
    TRAITDEF("Service") withTypeParams (
      TYPEVAR("RQ") UPPER (valueCache("RpcRequest"))
    ) := BLOCK(
        TYPEVAR("HandleResult") := REF("\\/") APPLYTYPE (
          "RpcError",
          "(RpcOk, Vector[(Long, Update)])"
        ),
        TYPEVAR("HandlerResult[A <: RpcResponse]") := REF("\\/") APPLYTYPE (
          "RpcError",
          "(A, Vector[(Long, Update)])"
        ),
        DEF("handleRequest", valueCache("Future[HandleResult]")) withParams (
          PARAM("authId", LongClass),
          PARAM("optUserId", optionType(IntClass)),
          PARAM("request", valueCache("RQ"))
        )
      )
  }

  def packageApiServiceTrees(packageName: String, items: Vector[Item]): Vector[Tree] = {
    val rpcs = items.filter(_.isInstanceOf[RpcContent])

    if (rpcs.isEmpty) {
      Vector.empty
    } else {
      val handlers: Vector[Tree] = rpcs map {
        case RpcContent(_, name, attributes, response) =>
          val params: Vector[ValDef] = Vector(
            PARAM("authId", LongClass): ValDef,
            PARAM("optUserId", optionType(IntClass)): ValDef
          ) ++ (attributes map { attr =>

            def scalaTyp(typ: Types.AttributeType): Type = typ match {
              case Types.Int32              => IntClass
              case Types.Int64              => LongClass
              case Types.Bool               => BooleanClass
              case Types.Double             => DoubleClass
              case Types.String             => StringClass
              case Types.Bytes              => arrayType(ByteClass)
              case Types.Enum(enumName)     => valueCache(enumName)
              case Types.Opt(optAttrType)   => optionType(scalaTyp(optAttrType))
              case Types.List(listAttrType) => vectorType(scalaTyp(listAttrType))
              case Types.Struct(structName) => valueCache(f"Refs.$structName%s")
              case Types.Trait(traitName)   => valueCache(f"Refs.$traitName%s")
            }

            PARAM(attr.name, scalaTyp(attr.typ)): ValDef
          })

          val respType = response match {
            case _: AnonymousRpcResponse => f"Response$name%s"
            case named: NamedRpcResponse => f"Refs.Response${named.name}%s"
          }

          (DEF(f"handle$name%s", valueCache(f"Future[HandlerResult[$respType%s]]")) withParams (
            params
          )).tree
      }

      val handleRequestDef = DEF("handleRequest", valueCache("Future[HandleResult]")) withParams(
        PARAM("authId", LongClass),
        PARAM("optUserId", optionType(IntClass)),
        PARAM("request", valueCache(f"${packageName.capitalize}%sRpcRequest"))
      ) := BLOCK(
        VAL("f") := REF("request") MATCH(
          rpcs map {
            case RpcContent(_, name, attributes, _) =>
              val rqParams: Vector[Tree] = (attributes map { attr =>
                REF("r") DOT(attr.name): Tree
              })

              val clientParams: Vector[Tree] = Vector(
                REF("authId"),
                REF("optUserId")
              )

              CASE(REF("r") withType(valueCache(f"Request$name%s"))) ==> (
                REF(f"handle$name%s") APPLY(
                  clientParams ++ rqParams
                )
              )
          }
        ),

        REF("f") DOT("map") APPLY(BLOCK(
          CASE(REF("\\/-") APPLY(TUPLE(REF("rsp"), REF("updates")))) ==> (
            REF("\\/-") APPLY(TUPLE(
              REF("RpcOk") APPLY(REF("rsp")),
              REF("updates")
            ))
          ),
          CASE(REF("err: -\\/[RpcError]")) ==> REF("err")
        ))
      )

      val ecDef: Tree = VAL("ec", valueCache("ExecutionContext")) withFlags(Flags.IMPLICIT)

      Vector(
        TRAITDEF(f"${packageName.capitalize}Service")
          withParents (f"Service[${packageName.capitalize}%sRpcRequest]") := BLOCK(
            Vector(ecDef, handleRequestDef) ++
              handlers
          )
      )
    }
  }
}
