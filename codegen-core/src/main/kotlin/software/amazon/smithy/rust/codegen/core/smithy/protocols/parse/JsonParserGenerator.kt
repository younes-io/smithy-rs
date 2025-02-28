/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.core.smithy.protocols.parse

import software.amazon.smithy.model.shapes.BlobShape
import software.amazon.smithy.model.shapes.BooleanShape
import software.amazon.smithy.model.shapes.CollectionShape
import software.amazon.smithy.model.shapes.DocumentShape
import software.amazon.smithy.model.shapes.MapShape
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.NumberShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.StringShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.shapes.TimestampShape
import software.amazon.smithy.model.shapes.UnionShape
import software.amazon.smithy.model.traits.EnumTrait
import software.amazon.smithy.model.traits.SparseTrait
import software.amazon.smithy.model.traits.TimestampFormatTrait
import software.amazon.smithy.rust.codegen.core.rustlang.Attribute
import software.amazon.smithy.rust.codegen.core.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.core.rustlang.RustModule
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.asType
import software.amazon.smithy.rust.codegen.core.rustlang.escape
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlockTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.withBlock
import software.amazon.smithy.rust.codegen.core.rustlang.withBlockTemplate
import software.amazon.smithy.rust.codegen.core.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.core.smithy.CodegenTarget
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.canUseDefault
import software.amazon.smithy.rust.codegen.core.smithy.generators.StructureGenerator
import software.amazon.smithy.rust.codegen.core.smithy.generators.TypeConversionGenerator
import software.amazon.smithy.rust.codegen.core.smithy.generators.UnionGenerator
import software.amazon.smithy.rust.codegen.core.smithy.generators.builderSymbol
import software.amazon.smithy.rust.codegen.core.smithy.generators.renderUnknownVariant
import software.amazon.smithy.rust.codegen.core.smithy.generators.setterName
import software.amazon.smithy.rust.codegen.core.smithy.isRustBoxed
import software.amazon.smithy.rust.codegen.core.smithy.protocols.HttpBindingResolver
import software.amazon.smithy.rust.codegen.core.smithy.protocols.HttpLocation
import software.amazon.smithy.rust.codegen.core.smithy.protocols.deserializeFunctionName
import software.amazon.smithy.rust.codegen.core.util.PANIC
import software.amazon.smithy.rust.codegen.core.util.dq
import software.amazon.smithy.rust.codegen.core.util.hasTrait
import software.amazon.smithy.rust.codegen.core.util.inputShape
import software.amazon.smithy.rust.codegen.core.util.outputShape
import software.amazon.smithy.utils.StringUtils

class JsonParserGenerator(
    private val codegenContext: CodegenContext,
    private val httpBindingResolver: HttpBindingResolver,
    /** Function that maps a MemberShape into a JSON field name */
    private val jsonName: (MemberShape) -> String,
) : StructuredDataParserGenerator {
    private val model = codegenContext.model
    private val symbolProvider = codegenContext.symbolProvider
    private val runtimeConfig = codegenContext.runtimeConfig
    private val target = codegenContext.target
    private val smithyJson = CargoDependency.smithyJson(runtimeConfig).asType()
    private val jsonDeserModule = RustModule.private("json_deser")
    private val typeConversionGenerator = TypeConversionGenerator(model, symbolProvider, runtimeConfig)
    private val codegenScope = arrayOf(
        "Error" to smithyJson.member("deserialize::Error"),
        "ErrorReason" to smithyJson.member("deserialize::ErrorReason"),
        "expect_blob_or_null" to smithyJson.member("deserialize::token::expect_blob_or_null"),
        "expect_bool_or_null" to smithyJson.member("deserialize::token::expect_bool_or_null"),
        "expect_document" to smithyJson.member("deserialize::token::expect_document"),
        "expect_number_or_null" to smithyJson.member("deserialize::token::expect_number_or_null"),
        "expect_start_array" to smithyJson.member("deserialize::token::expect_start_array"),
        "expect_start_object" to smithyJson.member("deserialize::token::expect_start_object"),
        "expect_string_or_null" to smithyJson.member("deserialize::token::expect_string_or_null"),
        "expect_timestamp_or_null" to smithyJson.member("deserialize::token::expect_timestamp_or_null"),
        "json_token_iter" to smithyJson.member("deserialize::json_token_iter"),
        "Peekable" to RuntimeType.std.member("iter::Peekable"),
        "skip_value" to smithyJson.member("deserialize::token::skip_value"),
        "skip_to_end" to smithyJson.member("deserialize::token::skip_to_end"),
        "Token" to smithyJson.member("deserialize::Token"),
        "or_empty" to orEmptyJson(),
    )

    /**
     * Reusable structure parser implementation that can be used to generate parsing code for
     * operation, error and structure shapes.
     * We still generate the parser symbol even if there are no included members because the server
     * generation requires parsers for all input structures.
     */
    private fun structureParser(
        fnName: String,
        structureShape: StructureShape,
        includedMembers: List<MemberShape>,
    ): RuntimeType {
        return RuntimeType.forInlineFun(fnName, jsonDeserModule) {
            val unusedMut = if (includedMembers.isEmpty()) "##[allow(unused_mut)] " else ""
            rustBlockTemplate(
                "pub fn $fnName(value: &[u8], ${unusedMut}mut builder: #{Builder}) -> Result<#{Builder}, #{Error}>",
                "Builder" to structureShape.builderSymbol(symbolProvider),
                *codegenScope,
            ) {
                rustTemplate(
                    """
                    let mut tokens_owned = #{json_token_iter}(#{or_empty}(value)).peekable();
                    let tokens = &mut tokens_owned;
                    #{expect_start_object}(tokens.next())?;
                    """,
                    *codegenScope,
                )
                deserializeStructInner(includedMembers)
                expectEndOfTokenStream()
                rust("Ok(builder)")
            }
        }
    }

    override fun payloadParser(member: MemberShape): RuntimeType {
        val shape = model.expectShape(member.target)
        check(shape is UnionShape || shape is StructureShape || shape is DocumentShape) { "payload parser should only be used on structures & unions" }
        val fnName = symbolProvider.deserializeFunctionName(shape) + "_payload"
        return RuntimeType.forInlineFun(fnName, jsonDeserModule) {
            rustBlockTemplate(
                "pub fn $fnName(input: &[u8]) -> Result<#{Shape}, #{Error}>",
                *codegenScope,
                "Shape" to symbolProvider.toSymbol(shape),
            ) {
                val input = if (shape is DocumentShape) {
                    "input"
                } else {
                    "#{or_empty}(input)"
                }

                rustTemplate(
                    """
                    let mut tokens_owned = #{json_token_iter}($input).peekable();
                    let tokens = &mut tokens_owned;
                    """,
                    *codegenScope,
                )
                rust("let result =")
                deserializeMember(member)
                rustTemplate(".ok_or_else(|| #{Error}::custom(\"expected payload member value\"));", *codegenScope)
                expectEndOfTokenStream()
                rust("result")
            }
        }
    }

    override fun operationParser(operationShape: OperationShape): RuntimeType? {
        // Don't generate an operation JSON deserializer if there is no JSON body
        val httpDocumentMembers = httpBindingResolver.responseMembers(operationShape, HttpLocation.DOCUMENT)
        if (httpDocumentMembers.isEmpty()) {
            return null
        }
        val outputShape = operationShape.outputShape(model)
        val fnName = symbolProvider.deserializeFunctionName(operationShape)
        return structureParser(fnName, outputShape, httpDocumentMembers)
    }

    override fun errorParser(errorShape: StructureShape): RuntimeType? {
        if (errorShape.members().isEmpty()) {
            return null
        }
        val fnName = symbolProvider.deserializeFunctionName(errorShape) + "_json_err"
        return structureParser(fnName, errorShape, errorShape.members().toList())
    }

    private fun orEmptyJson(): RuntimeType = RuntimeType.forInlineFun("or_empty_doc", jsonDeserModule) {
        rust(
            """
            pub fn or_empty_doc(data: &[u8]) -> &[u8] {
                if data.is_empty() {
                    b"{}"
                } else {
                    data
                }
            }
            """,
        )
    }

    override fun serverInputParser(operationShape: OperationShape): RuntimeType? {
        val includedMembers = httpBindingResolver.requestMembers(operationShape, HttpLocation.DOCUMENT)
        if (includedMembers.isEmpty()) {
            return null
        }
        val inputShape = operationShape.inputShape(model)
        val fnName = symbolProvider.deserializeFunctionName(operationShape)
        return structureParser(fnName, inputShape, includedMembers)
    }

    private fun RustWriter.expectEndOfTokenStream() {
        rustBlock("if tokens.next().is_some()") {
            rustTemplate(
                "return Err(#{Error}::custom(\"found more JSON tokens after completing parsing\"));",
                *codegenScope,
            )
        }
    }

    private fun RustWriter.deserializeStructInner(members: Collection<MemberShape>) {
        objectKeyLoop(hasMembers = members.isNotEmpty()) {
            rustBlock("match key.to_unescaped()?.as_ref()") {
                for (member in members) {
                    rustBlock("${jsonName(member).dq()} =>") {
                        withBlock("builder = builder.${member.setterName()}(", ");") {
                            deserializeMember(member)
                        }
                    }
                }
                rustTemplate("_ => #{skip_value}(tokens)?", *codegenScope)
            }
        }
    }

    private fun RustWriter.deserializeMember(memberShape: MemberShape) {
        when (val target = model.expectShape(memberShape.target)) {
            is StringShape -> deserializeString(target)
            is BooleanShape -> rustTemplate("#{expect_bool_or_null}(tokens.next())?", *codegenScope)
            is NumberShape -> deserializeNumber(target)
            is BlobShape -> deserializeBlob(target)
            is TimestampShape -> deserializeTimestamp(target, memberShape)
            is CollectionShape -> deserializeCollection(target)
            is MapShape -> deserializeMap(target)
            is StructureShape -> deserializeStruct(target)
            is UnionShape -> deserializeUnion(target)
            is DocumentShape -> rustTemplate("Some(#{expect_document}(tokens)?)", *codegenScope)
            else -> PANIC("unexpected shape: $target")
        }
        val symbol = symbolProvider.toSymbol(memberShape)
        if (symbol.isRustBoxed()) {
            rust(".map(Box::new)")
        }
    }

    private fun RustWriter.deserializeBlob(target: BlobShape) {
        rustTemplate(
            "#{expect_blob_or_null}(tokens.next())?#{ConvertFrom:W}",
            "ConvertFrom" to typeConversionGenerator.convertViaFrom(target),
            *codegenScope,
        )
    }

    private fun RustWriter.deserializeStringInner(target: StringShape, escapedStrName: String) {
        withBlock("$escapedStrName.to_unescaped().map(|u|", ")") {
            when (target.hasTrait<EnumTrait>()) {
                true -> {
                    if (convertsToEnumInServer(target)) {
                        rustTemplate(
                            """
                            #{EnumSymbol}::try_from(u.as_ref())
                                .map_err(|e| #{Error}::custom(format!("unknown variant {}", e)))
                            """,
                            "EnumSymbol" to symbolProvider.toSymbol(target),
                            *codegenScope,
                        )
                    } else {
                        rust("#T::from(u.as_ref())", symbolProvider.toSymbol(target))
                    }
                }
                else -> rust("u.into_owned()")
            }
        }
    }

    private fun convertsToEnumInServer(shape: StringShape) = target == CodegenTarget.SERVER && shape.hasTrait<EnumTrait>()

    private fun RustWriter.deserializeString(target: StringShape) {
        // Additional `.transpose()?` because we can't use `?` inside the closures that parsed the string.
        val additionalTranspose = ".transpose()?".repeat(if (convertsToEnumInServer(target)) 2 else 1)
        withBlockTemplate("#{expect_string_or_null}(tokens.next())?.map(|s|", ")$additionalTranspose", *codegenScope) {
            deserializeStringInner(target, "s")
        }
    }

    private fun RustWriter.deserializeNumber(target: NumberShape) {
        if (target.isFloatShape) {
            rustTemplate("#{expect_number_or_null}(tokens.next())?.map(|v| v.to_f32_lossy())", *codegenScope)
        } else if (target.isDoubleShape) {
            rustTemplate("#{expect_number_or_null}(tokens.next())?.map(|v| v.to_f64_lossy())", *codegenScope)
        } else {
            rustTemplate(
                """
                #{expect_number_or_null}(tokens.next())?
                    .map(|v| v.try_into())
                    .transpose()?
                """,
                *codegenScope,
            )
        }
    }

    private fun RustWriter.deserializeTimestamp(shape: TimestampShape, member: MemberShape) {
        val timestampFormat =
            httpBindingResolver.timestampFormat(
                member, HttpLocation.DOCUMENT,
                TimestampFormatTrait.Format.EPOCH_SECONDS,
            )
        val timestampFormatType = RuntimeType.TimestampFormat(runtimeConfig, timestampFormat)
        rustTemplate(
            "#{expect_timestamp_or_null}(tokens.next(), #{T})?#{ConvertFrom:W}",
            "T" to timestampFormatType, "ConvertFrom" to typeConversionGenerator.convertViaFrom(shape), *codegenScope,
        )
    }

    private fun RustWriter.deserializeCollection(shape: CollectionShape) {
        val fnName = symbolProvider.deserializeFunctionName(shape)
        val isSparse = shape.hasTrait<SparseTrait>()
        val parser = RuntimeType.forInlineFun(fnName, jsonDeserModule) {
            // Allow non-snake-case since some SDK models have lists with names prefixed with `__listOf__`,
            // which become `__list_of__`, and the Rust compiler warning doesn't like multiple adjacent underscores.
            rustBlockTemplate(
                """
                ##[allow(clippy::type_complexity, non_snake_case)]
                pub fn $fnName<'a, I>(tokens: &mut #{Peekable}<I>) -> Result<Option<#{Shape}>, #{Error}>
                    where I: Iterator<Item = Result<#{Token}<'a>, #{Error}>>
                """,
                "Shape" to symbolProvider.toSymbol(shape),
                *codegenScope,
            ) {
                startArrayOrNull {
                    rust("let mut items = Vec::new();")
                    rustBlock("loop") {
                        rustBlock("match tokens.peek()") {
                            rustBlockTemplate("Some(Ok(#{Token}::EndArray { .. })) =>", *codegenScope) {
                                rust("tokens.next().transpose().unwrap(); break;")
                            }
                            rustBlock("_ => ") {
                                if (isSparse) {
                                    withBlock("items.push(", ");") {
                                        deserializeMember(shape.member)
                                    }
                                } else {
                                    withBlock("let value =", ";") {
                                        deserializeMember(shape.member)
                                    }
                                    rustBlock("if let Some(value) = value") {
                                        rust("items.push(value);")
                                    }
                                }
                            }
                        }
                    }
                    rust("Ok(Some(items))")
                }
            }
        }
        rust("#T(tokens)?", parser)
    }

    private fun RustWriter.deserializeMap(shape: MapShape) {
        val keyTarget = model.expectShape(shape.key.target) as StringShape
        val fnName = symbolProvider.deserializeFunctionName(shape)
        val isSparse = shape.hasTrait<SparseTrait>()
        val parser = RuntimeType.forInlineFun(fnName, jsonDeserModule) {
            // Allow non-snake-case since some SDK models have maps with names prefixed with `__mapOf__`,
            // which become `__map_of__`, and the Rust compiler warning doesn't like multiple adjacent underscores.
            rustBlockTemplate(
                """
                ##[allow(clippy::type_complexity, non_snake_case)]
                pub fn $fnName<'a, I>(tokens: &mut #{Peekable}<I>) -> Result<Option<#{Shape}>, #{Error}>
                    where I: Iterator<Item = Result<#{Token}<'a>, #{Error}>>
                """,
                "Shape" to symbolProvider.toSymbol(shape),
                *codegenScope,
            ) {
                startObjectOrNull {
                    rust("let mut map = #T::new();", software.amazon.smithy.rust.codegen.core.rustlang.RustType.HashMap.RuntimeType)
                    objectKeyLoop(hasMembers = true) {
                        withBlock("let key =", "?;") {
                            deserializeStringInner(keyTarget, "key")
                        }
                        withBlock("let value =", ";") {
                            deserializeMember(shape.value)
                        }
                        if (convertsToEnumInServer(keyTarget)) {
                            rust("let key = key?;")
                        }
                        if (isSparse) {
                            rust("map.insert(key, value);")
                        } else {
                            rustBlock("if let Some(value) = value") {
                                rust("map.insert(key, value);")
                            }
                        }
                    }
                    rust("Ok(Some(map))")
                }
            }
        }
        rust("#T(tokens)?", parser)
    }

    private fun RustWriter.deserializeStruct(shape: StructureShape) {
        val fnName = symbolProvider.deserializeFunctionName(shape)
        val symbol = symbolProvider.toSymbol(shape)
        val nestedParser = RuntimeType.forInlineFun(fnName, jsonDeserModule) {
            rustBlockTemplate(
                """
                pub fn $fnName<'a, I>(tokens: &mut #{Peekable}<I>) -> Result<Option<#{Shape}>, #{Error}>
                    where I: Iterator<Item = Result<#{Token}<'a>, #{Error}>>
                """,
                "Shape" to symbol,
                *codegenScope,
            ) {
                startObjectOrNull {
                    Attribute.AllowUnusedMut.render(this)
                    rustTemplate("let mut builder = #{Shape}::builder();", *codegenScope, "Shape" to symbol)
                    deserializeStructInner(shape.members())
                    withBlock("Ok(Some(builder.build()", "))") {
                        if (StructureGenerator.hasFallibleBuilder(shape, symbolProvider)) {
                            rustTemplate(
                                """.map_err(|err| #{Error}::new(
                                #{ErrorReason}::Custom(format!("{}", err).into()), None)
                                )?""",
                                *codegenScope,
                            )
                        }
                    }
                }
            }
        }
        rust("#T(tokens)?", nestedParser)
    }

    private fun RustWriter.deserializeUnion(shape: UnionShape) {
        val fnName = symbolProvider.deserializeFunctionName(shape)
        val symbol = symbolProvider.toSymbol(shape)
        val nestedParser = RuntimeType.forInlineFun(fnName, jsonDeserModule) {
            rustBlockTemplate(
                """
                pub fn $fnName<'a, I>(tokens: &mut #{Peekable}<I>) -> Result<Option<#{Shape}>, #{Error}>
                    where I: Iterator<Item = Result<#{Token}<'a>, #{Error}>>
                """,
                *codegenScope,
                "Shape" to symbol,
            ) {
                rust("let mut variant = None;")
                rustBlock("match tokens.next().transpose()?") {
                    rustBlockTemplate(
                        """
                        Some(#{Token}::ValueNull { .. }) => return Ok(None),
                        Some(#{Token}::StartObject { .. }) =>
                        """,
                        *codegenScope,
                    ) {
                        objectKeyLoop(hasMembers = shape.members().isNotEmpty()) {
                            rustTemplate(
                                """
                                if variant.is_some() {
                                    return Err(#{Error}::custom("encountered mixed variants in union"));
                                }
                                """,
                                *codegenScope,
                            )
                            withBlock("variant = match key.to_unescaped()?.as_ref() {", "};") {
                                for (member in shape.members()) {
                                    val variantName = symbolProvider.toMemberName(member)
                                    rustBlock("${jsonName(member).dq()} =>") {
                                        withBlock("Some(#T::$variantName(", "))", symbol) {
                                            deserializeMember(member)
                                            unwrapOrDefaultOrError(member)
                                        }
                                    }
                                }
                                when (target.renderUnknownVariant()) {
                                    // in client mode, resolve an unknown union variant to the unknown variant
                                    true -> rustTemplate(
                                        """
                                        _ => {
                                          #{skip_value}(tokens)?;
                                          Some(#{Union}::${UnionGenerator.UnknownVariantName})
                                        }
                                        """,
                                        "Union" to symbol, *codegenScope,
                                    )
                                    // in server mode, use strict parsing
                                    false -> rustTemplate(
                                        """variant => return Err(#{Error}::custom(format!("unexpected union variant: {}", variant)))""",
                                        *codegenScope,
                                    )
                                }
                            }
                        }
                    }
                    rustTemplate(
                        """_ => return Err(#{Error}::custom("expected start object or null"))""",
                        *codegenScope,
                    )
                }
                rust("Ok(variant)")
            }
        }
        rust("#T(tokens)?", nestedParser)
    }

    private fun RustWriter.unwrapOrDefaultOrError(member: MemberShape) {
        if (symbolProvider.toSymbol(member).canUseDefault()) {
            rust(".unwrap_or_default()")
        } else {
            rustTemplate(
                ".ok_or_else(|| #{Error}::custom(\"value for '${escape(member.memberName)}' cannot be null\"))?",
                *codegenScope,
            )
        }
    }

    private fun RustWriter.objectKeyLoop(hasMembers: Boolean, inner: Writable) {
        if (!hasMembers) {
            rustTemplate("#{skip_to_end}(tokens)?;", *codegenScope)
        } else {
            rustBlock("loop") {
                rustBlock("match tokens.next().transpose()?") {
                    rustBlockTemplate(
                        """
                        Some(#{Token}::EndObject { .. }) => break,
                        Some(#{Token}::ObjectKey { key, .. }) =>
                        """,
                        *codegenScope,
                    ) {
                        inner()
                    }
                    rustTemplate(
                        """other => return Err(#{Error}::custom(format!("expected object key or end object, found: {:?}", other)))""",
                        *codegenScope,
                    )
                }
            }
        }
    }

    private fun RustWriter.startArrayOrNull(inner: Writable) = startOrNull("array", inner)
    private fun RustWriter.startObjectOrNull(inner: Writable) = startOrNull("object", inner)
    private fun RustWriter.startOrNull(objectOrArray: String, inner: Writable) {
        rustBlockTemplate("match tokens.next().transpose()?", *codegenScope) {
            rustBlockTemplate(
                """
                Some(#{Token}::ValueNull { .. }) => Ok(None),
                Some(#{Token}::Start${StringUtils.capitalize(objectOrArray)} { .. }) =>
                """,
                *codegenScope,
            ) {
                inner()
            }
            rustBlockTemplate("_ =>") {
                rustTemplate(
                    "Err(#{Error}::custom(\"expected start $objectOrArray or null\"))",
                    *codegenScope,
                )
            }
        }
    }
}
