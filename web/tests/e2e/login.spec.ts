import { randomUUID } from 'node:crypto'
import { expect, test, type Page } from '@playwright/test'

// 폭 360px(playwright.config.ts)에서 가로 스크롤이 없어야 한다(FR-021, SC-007).
async function expectNoHorizontalScroll(page: Page) {
  const { scrollWidth, innerWidth } = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    innerWidth: window.innerWidth,
  }))
  expect(scrollWidth).toBeLessThanOrEqual(innerWidth)
}

// 가짜 구글 서버(mock-oauth2-server)의 로그인 폼을 채운다. 실제 이메일 대신 example.com 주소를 쓴다.
async function loginWithFakeGoogle(page: Page) {
  const id = randomUUID()
  await page.goto('/')
  await page.getByRole('link', { name: 'Google 계정으로 로그인' }).click()
  await page.locator('input[name="username"]').fill(`e2e-${id}`)
  await page.locator('textarea[name="claims"]').fill(
    JSON.stringify({ email: `e2e-${id}@example.com`, email_verified: true, name: '테스트 사용자' }),
  )
  await page.locator('input[type="submit"]').click()
  await expect(page.getByText('아직 가구가 없습니다.')).toBeVisible()
}

test.describe('화면 폭 360px에서 가로 스크롤 없음', () => {
  test('로그인', async ({ page }) => {
    await page.goto('/')
    await expect(page.getByText('가족에게 받은 초대 링크로 들어왔다면 로그인한 뒤 들어갈 가구를 확인합니다.')).toBeVisible()
    await expectNoHorizontalScroll(page)
  })

  test('로그인 실패', async ({ page }) => {
    await page.goto('/login-failed')
    await expect(page.getByText('로그인하지 못했습니다. 다시 시도해 주세요.')).toBeVisible()
    await expectNoHorizontalScroll(page)
  })

  test('재인증 실패', async ({ page }) => {
    await page.goto('/reauth-failed')
    await expect(page.getByText('구글 계정 확인에 실패했습니다. 다시 시도해 주세요.')).toBeVisible()
    await expectNoHorizontalScroll(page)
  })

  test('재인증 실패: 다른 계정', async ({ page }) => {
    await page.goto('/reauth-failed?reason=account-mismatch')
    await expect(page.getByText('다른 구글 계정을 골라 로그인이 해제되었습니다. 다시 로그인해 주세요.')).toBeVisible()
    await expectNoHorizontalScroll(page)
  })

  test('가구 없음과 탈퇴 확인', async ({ page }) => {
    await loginWithFakeGoogle(page)
    // FR-002: 가구 없음 화면의 안내 셋과 가구 만들기 버튼
    await expect(page.getByText('가족과 함께 볼 기록을 모으려면 가구를 만드세요.')).toBeVisible()
    await expect(page.getByText('가족에게 받은 초대 링크가 있으면 그 링크를 여세요.')).toBeVisible()
    await expect(page.getByRole('button', { name: '가구 만들기' })).toBeVisible()
    await expectNoHorizontalScroll(page)

    // 탈퇴 요청은 보내지 않고 확인 화면만 연다
    await page.getByRole('link', { name: '탈퇴' }).click()
    await expect(page.getByText('탈퇴하면 계정, 로그인 이메일, 표시 이름과 모든 기기의 로그인 상태가 삭제되며 되돌릴 수 없습니다.')).toBeVisible()
    await expectNoHorizontalScroll(page)
  })
})

test('가짜 구글 로그인부터 로그아웃까지 한 바퀴', async ({ page }) => {
  await loginWithFakeGoogle(page)
  await expect(page).toHaveURL('/')

  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page.getByRole('link', { name: 'Google 계정으로 로그인' })).toBeVisible()
  expect((await page.request.get('/api/v1/me')).status()).toBe(401)
})
