export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} Б`;
  const unit = bytes < 1024 ** 2 ? 1024 : 1024 ** 2;
  return `${new Intl.NumberFormat('ru', { maximumFractionDigits: 1 }).format(bytes / unit)} ${unit === 1024 ? 'КБ' : 'МБ'}`;
}
export function formatDate(value: string): string {
  return new Intl.DateTimeFormat('ru', { day: 'numeric', month: 'short', year: 'numeric' }).format(
    new Date(value),
  );
}
