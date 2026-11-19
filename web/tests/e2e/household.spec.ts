import { expect, test, type Page } from '@playwright/test'
import { expectMobileLayout, loginWithFakeGoogle } from './support'

const NO_HOUSEHOLD = '아직 가구가 없습니다.'
const OWNER = '김집주'
const MEMBER = '이구성'
const RENAMED = '바뀐이름'

// owner가 초대 링크를 만들어 화면에 보이는 링크 글자를 돌려준다.
async function createInvite(owner: Page) {
  await owner.getByRole('button', { name: '초대 링크 만들기' }).click()
  const link = (await owner.getByText(/\/invite#\S+/).textContent())!
  expect(link).toMatch(/^http:\/\/localhost:\d+\/invite#\S+$/)
  return link
}

// 로그인한 사람이 링크를 열어 확인 화면에서 들어간다.
async function joinWithLink(member: Page, link: string, inviter: string) {
  await member.goto(link)
  await expect(member.getByText(`${inviter}님의 가구에 들어갑니다. 가족에게 받은 링크가 맞을 때만 들어가세요.`)).toBeVisible()
  await member.getByRole('button', { name: '들어가기' }).click()
  await expect(member.getByRole('heading', { name: '함께 보는 사람' })).toBeVisible()
  await expect(member).toHaveURL('/')
}

test('가구 만들기부터 가구 삭제까지 두 사람이 한 바퀴(360px 포함)', async ({ browser }) => {
  test.setTimeout(180_000)
  const ownerContext = await browser.newContext({ permissions: ['clipboard-read', 'clipboard-write'] })
  const memberContext = await browser.newContext()
  const owner = await ownerContext.newPage()
  const member = await memberContext.newPage()
  // browser.newContext()도 설정 파일의 뷰포트를 따르는지 확인한다
  expect(owner.viewportSize()).toEqual({ width: 360, height: 740 })
  expect(member.viewportSize()).toEqual({ width: 360, height: 740 })
  const row = (page: Page, email: string) => page.getByRole('listitem').filter({ hasText: email })

  await test.step('owner: 로그인, 가구 없음 화면, 가구 만들기, 임시 첫 화면', async () => {
    await owner.goto('/')
    const ownerEmail = await loginWithFakeGoogle(owner, OWNER)
    await expect(owner.getByText(NO_HOUSEHOLD)).toBeVisible()
    await expectMobileLayout(owner)
    await owner.getByRole('button', { name: '가구 만들기' }).click()
    await expect(owner.getByRole('heading', { name: '함께 보는 사람' })).toBeVisible()
    await expect(row(owner, ownerEmail)).toContainText(`${OWNER} (나) owner`)
    await expect(owner.getByRole('button', { name: '가구 삭제' })).toBeVisible()
    await expect(owner.getByRole('button', { name: '제거' })).toHaveCount(0)
    await expectMobileLayout(owner)
    test.info().annotations.push({ type: 'owner', description: ownerEmail })
  })

  let link1 = ''
  await test.step('owner: 초대 만들기, 링크 복사, 방금 만든 초대 화면', async () => {
    link1 = await createInvite(owner)
    await expect(owner.getByText('방금 만듦')).toBeVisible()
    await expect(owner.getByText('이 화면을 떠나면 링크를 다시 볼 수 없습니다. 가족에게만 보내세요.')).toBeVisible()
    await expect(owner.getByText('기다리는 초대가 있어 새 초대를 만들 수 없습니다.')).toBeVisible()
    await owner.getByRole('button', { name: '복사' }).click()
    await expect.poll(() => owner.evaluate(() => navigator.clipboard.readText())).toBe(link1)
    await expectMobileLayout(owner)
  })

  let memberEmail = ''
  await test.step('member: 링크를 열면 로그인 화면, 로그인하면 확인 화면, 들어가기', async () => {
    await member.goto(link1)
    await expect(member.getByRole('link', { name: 'Google 계정으로 로그인' })).toBeVisible()
    await expect(member.getByText('가족에게 받은 초대 링크로 들어왔다면 로그인한 뒤 들어갈 가구를 확인합니다.')).toBeVisible()
    expect(member.url()).not.toContain('#')
    await expectMobileLayout(member)

    memberEmail = await loginWithFakeGoogle(member, MEMBER)
    await expect(member.getByText(`${OWNER}님의 가구에 들어갑니다. 가족에게 받은 링크가 맞을 때만 들어가세요.`)).toBeVisible()
    expect(member.url()).not.toContain('#')
    await expectMobileLayout(member)

    await member.getByRole('button', { name: '들어가기' }).click()
    await expect(member.getByRole('heading', { name: '함께 보는 사람' })).toBeVisible()
    await expect(member).toHaveURL('/')
    await expect(member.getByRole('button', { name: '가구 나가기' })).toBeVisible()
    await expect(member.getByRole('button', { name: '가구 삭제' })).toHaveCount(0)
    await expectMobileLayout(member)
  })

  await test.step('양쪽 목록과 표시 이름 저장(US5-2)', async () => {
    await owner.reload()
    await expect(owner.getByRole('listitem').filter({ hasText: 'owner' })).toHaveCount(1)
    await expect(row(owner, memberEmail)).toContainText(`${MEMBER} member`)
    await expect(row(owner, memberEmail).getByRole('button', { name: '제거' })).toBeVisible()
    await expect(owner.getByText('구성원이 둘이라 새 초대를 만들 수 없습니다.')).toBeVisible()
    await expect(row(member, memberEmail)).toContainText(`${MEMBER} (나) member`)

    await member.getByPlaceholder('새 이름').fill(RENAMED)
    await member.getByRole('button', { name: '저장' }).click()
    await expect(row(member, memberEmail)).toContainText(`${RENAMED} (나) member`)
    await owner.reload()
    await expect(row(owner, memberEmail)).toContainText(`${RENAMED} member`)
  })

  await test.step('owner: 구성원 제거, member는 가구 없음 화면', async () => {
    await row(owner, memberEmail).getByRole('button', { name: '제거' }).click()
    await expect(row(owner, memberEmail)).toHaveCount(0)
    await expect(owner.getByRole('button', { name: '초대 링크 만들기' })).toBeVisible()
    await expect(owner.getByText('다시 확인해야 합니다', { exact: false })).toHaveCount(0)
    await member.reload()
    await expect(member.getByText(NO_HOUSEHOLD)).toBeVisible()
    await expectMobileLayout(member)
  })

  await test.step('member: 이미 쓴 링크를 다시 열면 실패 안내', async () => {
    await member.goto(link1)
    await expect(member.getByText('이미 사용된 초대 링크입니다. 가족에게 새 초대 링크를 받아 주세요.')).toBeVisible()
    await expectMobileLayout(member)
    await member.getByRole('button', { name: '첫 화면으로' }).click()
    await expect(member.getByText(NO_HOUSEHOLD)).toBeVisible()
  })

  await test.step('다시 초대와 수락, 가구 나가기', async () => {
    const link2 = await createInvite(owner)
    expect(link2).not.toBe(link1)
    await joinWithLink(member, link2, OWNER)

    await member.getByRole('button', { name: '가구 나가기' }).click()
    await expect(member).toHaveURL('/household/leave')
    await expect(
      member.getByText('가구를 나가면 이 가구의 기록을 볼 수 없습니다. 다시 들어오려면 새 초대 링크가 필요합니다.'),
    ).toBeVisible()
    await expectMobileLayout(member)
    await member.getByRole('button', { name: '나가기', exact: true }).click()
    await expect(member.getByText(NO_HOUSEHOLD)).toBeVisible()
    await expect(member).toHaveURL('/')
    await owner.reload()
    await expect(row(owner, memberEmail)).toHaveCount(0)
  })

  await test.step('다시 초대와 수락, 가구가 있는 채로 탈퇴하면 409 안내', async () => {
    await joinWithLink(member, await createInvite(owner), OWNER)

    await member.goto('/withdraw')
    await member.getByRole('button', { name: '탈퇴' }).click()
    await expect(member.getByText('가구를 나가거나 삭제한 뒤 탈퇴할 수 있습니다.')).toBeVisible()
    await expect(member).toHaveURL('/withdraw')
    await expectMobileLayout(member)
  })

  await test.step('owner: 가구 삭제, 양쪽 가구 없음 화면', async () => {
    await owner.reload()
    await expect(row(owner, memberEmail)).toBeVisible()
    await owner.getByRole('button', { name: '가구 삭제' }).click()
    await expect(owner).toHaveURL('/household/delete')
    await expect(owner.getByText('가구를 삭제하면 가구, 구성원 2명의 연결, 보낸 초대가 삭제되며 되돌릴 수 없습니다.')).toBeVisible()
    await expectMobileLayout(owner)
    await owner.getByRole('button', { name: '가구 삭제', exact: true }).click()
    await expect(owner.getByText(NO_HOUSEHOLD)).toBeVisible()
    await expect(owner).toHaveURL('/')
    await expect(owner.getByText('구글 계정을 한 번 더 확인해야 합니다', { exact: false })).toHaveCount(0)
    await expectMobileLayout(owner)

    await member.goto('/')
    await expect(member.getByText(NO_HOUSEHOLD)).toBeVisible()
    await expectMobileLayout(member)
  })

  await ownerContext.close()
  await memberContext.close()
})
