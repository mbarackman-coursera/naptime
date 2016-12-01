package org.coursera.naptime.ari.graphql.schema

import com.linkedin.data.DataMap
import org.coursera.naptime.ResourceName
import org.coursera.naptime.ari.graphql.SangriaGraphQlContext
import org.coursera.naptime.ari.graphql.SangriaGraphQlSchemaBuilder
import org.coursera.naptime.schema.Handler
import org.coursera.naptime.schema.HandlerKind
import org.coursera.naptime.schema.RelationType
import org.coursera.naptime.schema.Resource
import org.coursera.naptime.schema.ReverseRelationAnnotation
import sangria.schema.Context
import sangria.schema.Field
import sangria.schema.ListType
import sangria.schema.ObjectType
import sangria.schema.Value

import scala.collection.JavaConverters._

object NaptimePaginatedResourceField {

  val COMPLEXITY_COST = 10.0D

  sealed trait FieldRelation

  case class ForwardRelation(resourceName: String) extends FieldRelation
  case class ReverseRelation(annotation: ReverseRelationAnnotation) extends FieldRelation

  def build(
      schemaMetadata: SchemaMetadata,
      resourceName: String,
      fieldName: String,
      handlerOverride: Option[Handler] = None,
      fieldRelation: Option[FieldRelation]): Option[Field[SangriaGraphQlContext, DataMap]] = {

    val resource = schemaMetadata.getResource(resourceName)

    schemaMetadata.getSchema(resource).flatMap { schema =>
      val handlerOpt = (handlerOverride, fieldRelation) match {
        case (Some(handler), _) =>
          Some(handler)
        case (None, Some(ForwardRelation(_))) =>
          resource.handlers.find(_.kind == HandlerKind.MULTI_GET)
        case (None, Some(ReverseRelation(annotation))) =>
          annotation.relationType match {

            case RelationType.FINDER =>
              val finderName = annotation.arguments.get("q").getOrElse {throw new IllegalStateException("Finder reverse relation on " +
                  s"${annotation.resourceName} does not have a q parameter specified")
              }
              resource.handlers.find(_.name == finderName)

            case RelationType.MULTI_GET =>
              resource.handlers.find(_.kind == HandlerKind.MULTI_GET)

            case RelationType.$UNKNOWN =>
              None
          }
        case _ =>
          resource.handlers.find(_.kind == HandlerKind.MULTI_GET)
      }

      handlerOpt.map { handler =>

        val reverseRelationSpecifiedArguments = fieldRelation match {
          case Some(ReverseRelation(annotation)) => annotation.arguments.keySet
          case _ => Set[String]()
        }

        val arguments = SangriaGraphQlSchemaBuilder
          .generateHandlerArguments(handler, includePagination = true)
          .filterNot(_.name == "ids")
          .filterNot(arg => reverseRelationSpecifiedArguments.contains(arg.name))
        Field.apply[SangriaGraphQlContext, DataMap, Any, Any](
          name = fieldName,
          fieldType = getType(schemaMetadata, resourceName, fieldName),
          resolve = context => ParentContext(context),
          complexity = Some(
            (ctx, args, childScore) => {
              // API calls should count 10x, and we take limit into account because there could be
              // N child API calls for each response here
              val limit = args.arg(NaptimePaginationField.limitArgument)
              Math.max(limit / 10, 1) * COMPLEXITY_COST * childScore
            }),
          arguments = arguments)
      }
    }
  }

  //TODO(bryan): add arguments for pagination in here
  private[this] def getType(
      schemaMetadata: SchemaMetadata,
      resourceName: String,
      fieldName: String): ObjectType[SangriaGraphQlContext, ParentContext] = {

    val resource = schemaMetadata.getResource(resourceName)
    schemaMetadata.getSchema(resource).getOrElse {
      throw SchemaGenerationException(s"Cannot find schema for $resourceName")
    }

    ObjectType[SangriaGraphQlContext, ParentContext](
      name = formatPaginatedResourceName(resource),
      fieldsFn = () => {
        val elementType = NaptimeResourceField.getType(schemaMetadata, resourceName)
        val listType = ListType(elementType)
        List(
          Field.apply[SangriaGraphQlContext, ParentContext, Any, Any](
            name = "elements",
            fieldType = listType,
            resolve = getResolver(resourceName, fieldName)),
          Field.apply[SangriaGraphQlContext, ParentContext, Any, Any](
            name = "paging",
            fieldType = NaptimePaginationField.getField(resourceName, fieldName),
            resolve = context => context.value
          ))
      })
  }

  private[this] def getResolver(
      resourceName: String,
      fieldName: String): Context[SangriaGraphQlContext, ParentContext] => Value[SangriaGraphQlContext, Any] = {
    (context: Context[SangriaGraphQlContext, ParentContext]) => {

      val parsedResourceName = ResourceName.parse(resourceName).getOrElse {
        throw SchemaExecutionException(s"Cannot parse resource name from $resourceName")
      }
      val connection = context.ctx.response.data.get(parsedResourceName).map { objects =>
        val ids = Option(context.value.parentContext.value).map { parentElement =>
          // Nested Request
          val allIds = parentElement.getDataList(fieldName).asScala
          val startOption = context.value.parentContext.arg(NaptimePaginationField.startArgument)
          val limit = context.value.parentContext.arg(NaptimePaginationField.limitArgument)
          val idsWithStart = startOption.map(s => allIds.dropWhile(_ != s)).getOrElse(allIds)
          idsWithStart.take(limit)
        }.getOrElse {
          // Top-Level Request
          context.ctx.response.topLevelResponses.find { case (topLevelRequest, _) =>
            topLevelRequest.resource.identifier == resourceName &&
              topLevelRequest.selection.alias ==
                context.value.parentContext.astFields.headOption.flatMap(_.alias)
          }.map(_._2.ids.asScala).getOrElse(List.empty)
        }
        objects.collect {
          case (id, element) if ids.contains(id) => element
        }.toSeq
      }.getOrElse(List.empty)

      Value[SangriaGraphQlContext, Any](connection)
    }
  }

  /**
    * Converts a resource name to a GraphQL compatible name. (i.e. 'courses.v1' to 'CoursesV1')
    *
    * @param resource Naptime resource
    * @return GraphQL-safe resource name
    */
  private[this] def formatPaginatedResourceName(resource: Resource): String = {
    s"${resource.name.capitalize}V${resource.version.getOrElse(0)}Connection"
  }

}