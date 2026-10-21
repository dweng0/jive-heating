import { useId } from 'react'
import { TriangleAlert } from 'lucide-react'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'

/**
 * The warning triangle on a plug's figures when some readings were missing: the cost shown is only so far.
 * With a [message] it is a button read as "Cost incomplete" with the message as its description, so a
 * screen reader gets it without a tap; a tap shows it.
 */
export function IncompleteWarning({ missingMinutes, message }: { missingMinutes: number; message?: string }) {
  const id = useId()
  if (message) {
    return (
      <Popover>
        <PopoverTrigger
          data-testid="incomplete-warning"
          aria-label="Cost incomplete"
          aria-describedby={id}
          className="inline-flex rounded text-heat-text focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none [&_svg]:size-4"
        >
          <TriangleAlert />
          <span id={id} className="sr-only">{message}</span>
        </PopoverTrigger>
        <PopoverContent className="w-auto max-w-72">{message}</PopoverContent>
      </Popover>
    )
  }
  const title = `Incomplete: ${missingMinutes} min with no reading, so this is the cost so far`
  return (
    <span data-testid="incomplete-warning" title={title} aria-label={title} role="img" className="inline-flex text-heat-text [&_svg]:size-4">
      <TriangleAlert />
    </span>
  )
}
