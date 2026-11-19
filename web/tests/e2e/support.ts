import { randomUUID } from 'node:crypto'
import { expect, type Page } from '@playwright/test'

// 폭 360px(playwright.config.ts)에서 가로 스크롤이 없어야 한다(FR-021, SC-007).
export async function expectNoHorizontalScroll(page: Page) {
  const { scrollWidth, innerWidth } = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    innerWidth: window.innerWidth,
  }))
  expect(scrollWidth).toBeLessThanOrEqual(innerWidth)
}

// 가짜 구글 서버(mock-oauth2-server)의 로그인 폼을 채운다. 로그인 화면에서 시작하고, 로그인 뒤 화면은 호출하는 쪽이 확인한다.
// 실제 이메일 대신 example.com 주소를 쓰고, 그 주소를 돌려준다.
export async function loginWithFakeGoogle(page: Page, name = '테스트 사용자') {
  const id = randomUUID()
  const email = `e2e-${id}@example.com`
  await page.getByRole('link', { name: 'Google 계정으로 로그인' }).click()
  await page.locator('input[name="username"]').fill(`e2e-${id}`)
  await page.locator('textarea[name="claims"]').fill(JSON.stringify({ email, email_verified: true, name }))
  await page.locator('input[type="submit"]').click()
  return email
}

// 가로 스크롤이 없고, 화면 안 button과 a.button이 모두 보이고 누를 수 있으며 뷰포트 너비 안에 들어온다(FR-029, SC-010).
export async function expectMobileLayout(page: Page) {
  await expectNoHorizontalScroll(page)
  const width = page.viewportSize()!.width
  const buttons = await page.locator('button, a.button').all()
  expect(buttons.length).toBeGreaterThan(0)
  for (const button of buttons) {
    await expect(button).toBeVisible()
    await expect(button).toBeEnabled()
    const box = (await button.boundingBox())!
    expect(box.x).toBeGreaterThanOrEqual(0)
    expect(box.x + box.width).toBeLessThanOrEqual(width)
  }
}
