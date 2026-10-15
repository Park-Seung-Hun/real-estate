// 이 폴더의 모든 .wireloom 원문을 같은 이름의 .svg로 생성합니다.
// 사용: npm install && npm run render
import { readdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import wireloom from 'wireloom';

const dir = dirname(fileURLToPath(import.meta.url));
const sources = (await readdir(dir)).filter((n) => n.endsWith('.wireloom')).sort();
for (const name of sources) {
  const id = name.replace(/\.wireloom$/, '');
  const source = await readFile(join(dir, name), 'utf8');
  const { svg } = await wireloom.render(id, source);
  await writeFile(join(dir, `${id}.svg`), svg);
  console.log(`rendered ${id}.svg`);
}
