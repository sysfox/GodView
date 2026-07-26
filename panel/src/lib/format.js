/* Formatting, in the shapes an operator reads without thinking. */

export function num(value, decimals = 0) {
  if (value === null || value === undefined || Number.isNaN(value)) return '--';
  return Number(value).toFixed(decimals);
}

export function bytes(value) {
  if (!value) return '0 MB';
  const mb = value / 1048576;
  if (mb >= 1024) return `${(mb / 1024).toFixed(1)} GB`;
  return `${Math.round(mb)} MB`;
}

/** "just now", "12m ago", "3d ago". */
export function since(millis) {
  if (!millis) return 'never';
  const delta = Date.now() - millis;
  if (delta < 45000) return 'just now';
  const minutes = Math.round(delta / 60000);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 48) return `${hours}h ago`;
  return `${Math.round(hours / 24)}d ago`;
}

export function duration(millis) {
  if (!millis) return '0m';
  const seconds = Math.floor(millis / 1000);
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days > 0) return `${days}d ${hours}h`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  return `${minutes}m`;
}

export function clock(millis) {
  return new Date(millis).toLocaleTimeString(undefined, { hour12: false });
}

export function stamp(millis) {
  const date = new Date(millis);
  const day = date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
  return `${day} ${clock(millis)}`;
}

/** Severity and event types map to the same three tones used everywhere. */
export function severityTone(severity) {
  if (severity === 'critical') return 'bad';
  if (severity === 'warn') return 'warn';
  return null;
}

export function eventTone(type) {
  if (type === 'console_error' || type === 'kick' || type === 'ban') return 'bad';
  if (type === 'death' || type === 'command') return 'warn';
  return null;
}
