import { expect, test, type Page } from '@playwright/test'
import { expectNoHorizontalScroll, loginWithFakeGoogle } from './support'

// 첫 화면에서 로그인해 가구 없음 화면까지 간다.
async function loginToNoHousehold(page: Page) {
  await page.goto('/')
  await loginWithFakeGoogle(page)
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
    await loginToNoHousehold(page)
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
  await loginToNoHousehold(page)
  await expect(page).toHaveURL('/')

  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page.getByRole('link', { name: 'Google 계정으로 로그인' })).toBeVisible()
  expect((await page.request.get('/api/v1/me')).status()).toBe(401)
})
