/* Formatting, in the shapes an operator reads without thinking. */

import { t, bcp47 } from './i18n.js';

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

/** "just now", "12m ago", "3d ago" -- and the same shapes in Chinese. */
export function since(millis) {
  if (!millis) return t('time.never');
  const delta = Date.now() - millis;
  if (delta < 45000) return t('time.justNow');
  const minutes = Math.round(delta / 60000);
  if (minutes < 60) return t('time.minutesAgo', { n: minutes });
  const hours = Math.round(minutes / 60);
  if (hours < 48) return t('time.hoursAgo', { n: hours });
  return t('time.daysAgo', { n: Math.round(hours / 24) });
}

export function duration(millis) {
  if (!millis) return t('time.minutes', { n: 0 });
  const seconds = Math.floor(millis / 1000);
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days > 0) return t('time.daysHours', { d: days, h: hours });
  if (hours > 0) return t('time.hoursMinutes', { h: hours, m: minutes });
  return t('time.minutes', { n: minutes });
}

/** A span, for chart axes: seconds, minutes or hours, whichever reads shortest. */
export function span(millis) {
  const minutes = Math.round(millis / 60000);
  if (minutes < 1) return t('time.seconds', { n: Math.round(millis / 1000) });
  if (minutes < 60) return t('time.minutes', { n: minutes });
  return t('time.hoursMinutes', { h: Math.round(minutes / 60), m: 0 });
}

export function clock(millis) {
  return new Date(millis).toLocaleTimeString(bcp47(), { hour12: false });
}

export function stamp(millis) {
  const date = new Date(millis);
  const day = date.toLocaleDateString(bcp47(), { month: 'short', day: 'numeric' });
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

/* Server-side vocabularies. Unknown values pass through untranslated rather
   than turning into a key, because a new event type added by a later version
   should still be readable. */

export function eventLabel(type) {
  const key = `eventType.${type}`;
  const label = t(key);
  return label === key ? String(type).replace(/_/g, ' ') : label;
}

export function severityLabel(severity) {
  const key = `severity.${severity}`;
  const label = t(key);
  return label === key ? severity : label;
}

export function roleLabel(role) {
  const key = `role.${role}`;
  const label = t(key);
  return label === key ? role : label;
}
