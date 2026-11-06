import type { FileInfo } from '../api/types';

export function fileComparator(sort: string): (a: FileInfo, b: FileInfo) => number {
  if (sort === 'name') return (a, b) => a.name.localeCompare(b.name, 'ru');
  if (sort === 'size') return (a, b) => b.sizeBytes - a.sizeBytes;
  return (a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt);
}
