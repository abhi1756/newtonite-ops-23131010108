const relative = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
const absolute = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });

export function timeAgo(iso: string | undefined): string {
  if (!iso) return '';
  const seconds = Math.round((new Date(iso).getTime() - Date.now()) / 1000);
  const abs = Math.abs(seconds);
  if (abs < 60) return relative.format(seconds, 'second');
  if (abs < 3600) return relative.format(Math.round(seconds / 60), 'minute');
  if (abs < 86400) return relative.format(Math.round(seconds / 3600), 'hour');
  if (abs < 86400 * 30) return relative.format(Math.round(seconds / 86400), 'day');
  return absolute.format(new Date(iso));
}

export function dateTime(iso: string | undefined): string {
  return iso ? absolute.format(new Date(iso)) : '-';
}

/** OPEN_THING -> "Open thing" */
export function label(value: string): string {
  const lower = value.replace(/_/g, ' ').toLowerCase();
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}

/** ISO instant -> value for <input type="datetime-local"> in local time. */
export function toLocalInput(iso: string | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  const offset = d.getTimezoneOffset() * 60_000;
  return new Date(d.getTime() - offset).toISOString().slice(0, 16);
}

export function fromLocalInput(value: string): string | undefined {
  return value ? new Date(value).toISOString() : undefined;
}
