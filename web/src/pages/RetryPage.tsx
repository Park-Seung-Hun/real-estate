export function RetryPage({ onRetry }: { onRetry: () => void }) {
  return (
    <>
      <p>잠시 후 다시 시도해 주세요.</p>
      <button className="button" type="button" onClick={onRetry}>
        다시 시도
      </button>
    </>
  )
}
