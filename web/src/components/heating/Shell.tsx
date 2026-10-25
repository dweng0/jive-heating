import { useState, type ReactNode } from 'react'
import { NavLink } from 'react-router-dom'
import { ChartLine, CircleUser, Flame, LogOut, Palmtree, Smartphone, Users, Zap } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { BoilerChip } from '@/components/heating/BoilerChip'
import { BoilerOverrideNotice } from '@/components/heating/BoilerOverrideNotice'
import { HolidayDialog } from '@/components/heating/HolidayDialog'
import { useHome } from '@/lib/home'
import { dateTime, formatTemp } from '@/lib/format'
import { cn } from '@/lib/utils'

export function Shell({ children, onSignOut }: { children: ReactNode; onSignOut: () => void }) {
  const { me, status } = useHome()
  const [holidayOpen, setHolidayOpen] = useState(false)
  const holiday = status?.holiday

  return (
    <div className="min-h-svh">
      <header className="sticky top-0 z-30 border-b border-border/60 bg-background/80 backdrop-blur-xl">
        <div className="mx-auto flex h-16 max-w-6xl items-center gap-4 px-4 sm:px-6">
          <NavLink to="/" className="flex items-center gap-2.5 font-bold tracking-tight">
            <span className="flex size-9 items-center justify-center rounded-xl bg-heat text-primary-foreground shadow-md shadow-heat/30">
              <Flame className="size-5" />
            </span>
            <span className="text-lg">Heating</span>
          </NavLink>

          <nav className="ml-4 hidden items-center gap-1 sm:flex">
            <TopLink to="/">Rooms</TopLink>
            {me.admin && <TopLink to="/dashboard">Dashboard</TopLink>}
            {me.admin && <TopLink to="/energy">Energy</TopLink>}
            {me.admin && <TopLink to="/people">People</TopLink>}
          </nav>

          <div className="ml-auto flex items-center gap-2">
            {/* The boiler and holiday are the whole house's: admins only. */}
            <BoilerOverrideNotice />
            {me.admin && (
              <>
                <BoilerChip />
                <Button variant="ghost" size="icon-lg" onClick={() => setHolidayOpen(true)} aria-label="Holiday">
                  <Palmtree className={cn(holiday && 'text-heat')} />
                </Button>
              </>
            )}

            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" size="icon-lg" aria-label="Account menu">
                  <CircleUser />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-52">
                <DropdownMenuLabel>
                  <div className="font-semibold">{me.username}</div>
                  <div className="text-xs font-normal text-muted-foreground">{me.admin ? 'Admin' : 'Member'}</div>
                </DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem asChild>
                  <NavLink to="/account">
                    <CircleUser /> Account
                  </NavLink>
                </DropdownMenuItem>
                <DropdownMenuItem asChild>
                  <NavLink to="/account#android-app">
                    <Smartphone /> Get the Android app
                  </NavLink>
                </DropdownMenuItem>
                {me.admin && (
                  <DropdownMenuItem asChild className="sm:hidden">
                    <NavLink to="/dashboard">
                      <ChartLine /> Dashboard
                    </NavLink>
                  </DropdownMenuItem>
                )}
                {me.admin && (
                  <DropdownMenuItem asChild className="sm:hidden">
                    <NavLink to="/energy">
                      <Zap /> Energy
                    </NavLink>
                  </DropdownMenuItem>
                )}
                {me.admin && (
                  <DropdownMenuItem asChild className="sm:hidden">
                    <NavLink to="/people">
                      <Users /> People
                    </NavLink>
                  </DropdownMenuItem>
                )}
                <DropdownMenuSeparator />
                <DropdownMenuItem onSelect={onSignOut}>
                  <LogOut /> Sign out
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>
      </header>

      {holiday && (
        <div className="border-b border-heat/20 bg-heat-container text-heat-text">
          <div className="mx-auto flex max-w-6xl items-center gap-3 px-4 py-2.5 text-sm sm:px-6">
            <Palmtree className="size-4 shrink-0" />
            <span className="font-medium">
              {holiday.active ? 'Holiday' : 'Holiday from ' + dateTime(holiday.from)}: every room at {formatTemp(holiday.target)} until{' '}
              {dateTime(holiday.until)}
            </span>
            <Button variant="link" size="sm" className="ml-auto text-heat-text" onClick={() => setHolidayOpen(true)}>
              Change
            </Button>
          </div>
        </div>
      )}

      <main className="mx-auto max-w-6xl px-4 py-8 sm:px-6">{children}</main>
      {me.admin && <HolidayDialog open={holidayOpen} onOpenChange={setHolidayOpen} />}
    </div>
  )
}

function TopLink({ to, children }: { to: string; children: ReactNode }) {
  return (
    <NavLink
      to={to}
      end
      className={({ isActive }) =>
        cn(
          'rounded-lg px-3 py-1.5 text-sm font-medium transition-colors',
          isActive ? 'bg-secondary text-foreground' : 'text-muted-foreground hover:text-foreground',
        )
      }
    >
      {children}
    </NavLink>
  )
}
