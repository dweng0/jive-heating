import { useCallback, useEffect, useState } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { Toaster } from '@/components/ui/sonner'
import { TooltipProvider } from '@/components/ui/tooltip'
import { Shell } from '@/components/heating/Shell'
import { api, setSessionEndedHandler, type User } from '@/lib/api'
import { HomeProvider } from '@/lib/home'
import { AccountPage } from '@/pages/Account'
import { DashboardPage } from '@/pages/Dashboard'
import { EnergyManagePage } from '@/pages/EnergyManage'
import { EnergyPage } from '@/pages/Energy'
import { EverythingElseEnergyPage, RoomEnergyPage } from '@/pages/RoomEnergy'
import { AreaEnergyPage } from '@/pages/AreaEnergy'
import { GroupPage } from '@/pages/GroupPage'
import { PeoplePage } from '@/pages/People'
import { RoomPage } from '@/pages/RoomPage'
import { RoomsPage } from '@/pages/Rooms'
import { SignInPage } from '@/pages/SignIn'
import { SetupGate } from '@/components/heating/SetupGate'
import { ConnectionSettingsPage } from '@/pages/ConnectionSettings'

type Auth = { state: 'loading' } | { state: 'signedOut'; notice?: string } | { state: 'signedIn'; me: User }

export default function App() {
  const [auth, setAuth] = useState<Auth>({ state: 'loading' })

  useEffect(() => {
    setSessionEndedHandler(() => setAuth({ state: 'signedOut', notice: 'Your session has ended. Please sign in again.' }))
    api.me().then(
      (me) => setAuth({ state: 'signedIn', me }),
      () => setAuth({ state: 'signedOut' }),
    )
  }, [])

  const signedIn = useCallback((me: User) => setAuth({ state: 'signedIn', me }), [])

  const signOut = useCallback(async () => {
    await api.logout().catch(() => undefined)
    setAuth({ state: 'signedOut' })
  }, [])

  return (
    <TooltipProvider>
      {auth.state === 'loading' && <div className="min-h-svh" />}
      {auth.state === 'signedOut' && (
        <SignInPage notice={auth.notice} onSignedIn={(me) => setAuth({ state: 'signedIn', me })} />
      )}
      {auth.state === 'signedIn' && (
        <HomeProvider me={auth.me} onMe={signedIn}>
          <Shell onSignOut={signOut}>
            <SetupGate>
            <Routes>
              <Route path="/" element={<RoomsPage />} />
              <Route path="/dashboard" element={auth.me.admin ? <DashboardPage /> : <Navigate to="/" replace />} />
              <Route path="/energy" element={auth.me.admin || auth.me.energy ? <EnergyPage /> : <Navigate to="/" replace />} />
              <Route path="/energy/manage" element={auth.me.admin ? <EnergyManagePage /> : <Navigate to="/" replace />} />
              <Route path="/energy/rooms/:id" element={auth.me.admin ? <RoomEnergyPage /> : <Navigate to="/" replace />} />
              <Route path="/energy/areas/:id" element={auth.me.admin ? <AreaEnergyPage /> : <Navigate to="/" replace />} />
              <Route path="/energy/else" element={auth.me.admin ? <EverythingElseEnergyPage /> : <Navigate to="/" replace />} />
              <Route path="/rooms/:id" element={<RoomPage />} />
              <Route path="/groups/:id" element={auth.me.admin ? <GroupPage /> : <Navigate to="/" replace />} />
              <Route path="/account" element={<AccountPage onSignOut={signOut} onSignedOutEverywhere={() => setAuth({ state: 'signedOut' })} />} />
              <Route path="/people" element={auth.me.admin ? <PeoplePage /> : <Navigate to="/" replace />} />
              <Route path="*" element={<Navigate to="/" replace />} />
              <Route path="/settings/connection" element={auth.me.admin ? <ConnectionSettingsPage /> : <Navigate to="/" replace />} />
            </Routes>
            </SetupGate>
          </Shell>
        </HomeProvider>
      )}
      <Toaster position="bottom-center" richColors />
    </TooltipProvider>
  )
}
