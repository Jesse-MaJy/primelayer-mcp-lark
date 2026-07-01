const CHINA_TIME_ZONE = 'Asia/Shanghai'

const chinaDateTimeFormatter = new Intl.DateTimeFormat('zh-CN', {
  timeZone: CHINA_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false
})

const chinaDateFormatter = new Intl.DateTimeFormat('zh-CN', {
  timeZone: CHINA_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  weekday: 'short'
})

export function formatChinaDateTime(value: unknown) {
  if (value == null || value === '') {
    return '-'
  }
  if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(value)) {
    return value
  }

  const date = value instanceof Date ? value : new Date(String(value))
  if (Number.isNaN(date.getTime())) {
    return String(value)
  }

  const parts = Object.fromEntries(
    chinaDateTimeFormatter.formatToParts(date).map((part) => [part.type, part.value])
  )
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`
}

export function formatChinaDate(value: unknown = new Date()) {
  const date = value instanceof Date ? value : new Date(String(value))
  if (Number.isNaN(date.getTime())) {
    return String(value)
  }
  return chinaDateFormatter.format(date).replace(/\//g, '-')
}

export function timeColumn(title: string, dataIndex: string, extra: Record<string, unknown> = {}) {
  return {
    title,
    dataIndex,
    customRender: ({ text }: { text: unknown }) => formatChinaDateTime(text),
    ...extra
  }
}
