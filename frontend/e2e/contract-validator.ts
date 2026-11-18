/**
 * 极简 OpenAPI 响应结构校验器（仅覆盖契约中用到的子集）：
 * $ref / type / required / properties / items / nullable。
 *
 * 不引入 JSON Schema 依赖，避免为一次契约冒烟引入额外供应链；
 * 关键响应结构仍以 contracts/openapi.json 为唯一事实来源。
 */

export interface OpenApiSchema {
  $ref?: string
  type?: string
  nullable?: boolean
  required?: string[]
  properties?: Record<string, OpenApiSchema>
  items?: OpenApiSchema
}

export interface OpenApiOperation {
  responses?: Record<string, {
    content?: Record<string, { schema?: OpenApiSchema }>
  }>
}

export interface OpenApiDoc {
  paths: Record<string, Record<string, OpenApiOperation>>
  components?: { schemas?: Record<string, OpenApiSchema> }
}

function resolveRef(schema: OpenApiSchema, doc: OpenApiDoc): OpenApiSchema {
  if (!schema.$ref) return schema
  const name = schema.$ref.split('/').pop()
  return name ? doc.components?.schemas?.[name] ?? {} : {}
}

function actualType(value: unknown): string {
  if (Array.isArray(value)) return 'array'
  if (typeof value === 'number') return Number.isInteger(value) ? 'integer' : 'number'
  return typeof value
}

export function validateAgainstSchema(
  value: unknown,
  schema: OpenApiSchema | undefined,
  doc: OpenApiDoc,
  path = '$'
): string[] {
  if (!schema) return []
  const resolved = resolveRef(schema, doc)

  if (value === null || value === undefined) {
    return resolved.nullable ? [] : [`${path} 不应为空`]
  }

  if (resolved.type) {
    const actual = actualType(value)
    const ok = resolved.type === actual || (resolved.type === 'number' && actual === 'integer')
    if (!ok) return [`${path} 期望 ${resolved.type}，实际 ${actual}`]
  }

  const errors: string[] = []

  if (resolved.type === 'object' || resolved.properties) {
    if (typeof value !== 'object' || Array.isArray(value)) {
      return [`${path} 期望对象，实际 ${actualType(value)}`]
    }
    const record = value as Record<string, unknown>
    for (const key of resolved.required ?? []) {
      if (!(key in record)) errors.push(`${path}.${key} 缺失`)
    }
    for (const [key, childSchema] of Object.entries(resolved.properties ?? {})) {
      if (key in record) {
        errors.push(...validateAgainstSchema(record[key], childSchema, doc, `${path}.${key}`))
      }
    }
  }

  if (resolved.type === 'array' && resolved.items && Array.isArray(value)) {
    value.forEach((item, index) => {
      errors.push(...validateAgainstSchema(item, resolved.items, doc, `${path}[${index}]`))
    })
  }

  return errors
}
