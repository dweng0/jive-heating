import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { toast } from 'sonner'
import { api, followLive, type ActivityEvent, type BoilerOverride, type Group, type LiveEvent, type Room, type Status, type User } from './api'

interface Home {
  me: User
  rooms: Room[]
  groups: Group[]
  status: Status | null
  /** Set while an admin has forced the boiler off. Every user is told. */
  boilerOverride: BoilerOverride | null
  setBoilerOverride: (override: BoilerOverride | null) => void
  loading: boolean
  live: boolean
  /** The newest event pushed on /ws, for the activity tray to add to its list. */
  latestActivity: ActivityEvent | null
  /** The household's boost temperature: where every boost starts. 22 until it has loaded. */
  boostTarget: number
  setBoostTarget: (target: number) => void
  refresh: () => Promise<void>
  /** Replace one room after an action returned it, before the live event arrives. */
  putRoom: (room: Room) => void
  setStatus: (status: Status) => void
  /** Replace or add one group after an action returned it. */
  putGroup: (group: Group) => void
  removeGroup: (id: string) => void
  /** Run an action; on failure show the server's message. */
  run: <T>(action: () => Promise<T>, failure?: string) => Promise<T | undefined>
}

const HomeContext = createContext<Home | null>(null)

const DEFAULT_BOOST_TARGET = 22

/**
 * Rooms, groups and status for the signed-in user, kept fresh from /ws. When an admin changes this
 * user's rooms or admin rights, the user is fetched again and handed to [onMe].
 */
export function HomeProvider({ me, onMe, children }: { me: User; onMe: (me: User) => void; children: ReactNode }) {
  const [rooms, setRooms] = useState<Room[]>([])
  const [groups, setGroups] = useState<Group[]>([])
  const [status, setStatus] = useState<Status | null>(null)
  const [boilerOverride, setBoilerOverride] = useState<BoilerOverride | null>(null)
  const [loading, setLoading] = useState(true)
  const [live, setLive] = useState(false)
  const [latestActivity, setLatestActivity] = useState<ActivityEvent | null>(null)
  const [boostTarget, setBoostTarget] = useState(DEFAULT_BOOST_TARGET)

  // Members only see their own rooms: the house status and groups are admin only, so never ask.
  const admin = me.admin
  const refresh = useCallback(async () => {
    // Every user can read it. If it can't be loaded, boosts still start at the old default.
    api.boostSettings().then((s) => setBoostTarget(s.defaultTarget), () => undefined)
    api.boiler().then((s) => setBoilerOverride(s.override), () => undefined)
    try {
      if (!admin) {
        setRooms(await api.rooms())
        return
      }
      const [r, s, g] = await Promise.all([api.rooms(), api.status(), api.groups()])
      setRooms(r)
      setStatus(s)
      setGroups(g)
    } catch (e) {
      toast.error((e as Error).message)
    } finally {
      setLoading(false)
    }
  }, [admin])

  const putRoom = useCallback((room: Room) => {
    setRooms((rs) => (rs.some((r) => r.id === room.id) ? rs.map((r) => (r.id === room.id ? room : r)) : [...rs, room]))
  }, [])

  const putGroup = useCallback((group: Group) => {
    setGroups((gs) => (gs.some((g) => g.id === group.id) ? gs.map((g) => (g.id === group.id ? group : g)) : [...gs, group]))
  }, [])

  const removeGroup = useCallback((id: string) => setGroups((gs) => gs.filter((g) => g.id !== id)), [])

  useEffect(() => {
    void refresh()
    const apply = (e: LiveEvent) => {
      // A member only follows rooms they were given; the service decides which those are.
      if (e.type === 'room.updated' && e.room) {
        const room = e.room
        if (admin) putRoom(room)
        else setRooms((rs) => rs.map((r) => (r.id === room.id ? room : r)))
      }
      else if (e.type === 'room.deleted' && e.roomId) setRooms((rs) => rs.filter((r) => r.id !== e.roomId))
      else if (e.type === 'status.updated' && e.status && admin) setStatus(e.status)
      else if (e.type === 'boiler.override' && e.boilerOverride) setBoilerOverride(e.boilerOverride.override)
      else if (e.type === 'groups.updated' && e.groups && admin) setGroups(e.groups)
      else if (e.type === 'activity.added' && e.activity) setLatestActivity(e.activity)
      else if (e.type === 'access.updated') {
        // Rooms given show up and rooms taken away go, straight away.
        api.me().then(onMe, () => undefined)
        void refresh()
      }
    }
    // Catch up on anything missed while the socket was down.
    return followLive(apply, (connected) => {
      setLive(connected)
      if (connected) void refresh()
    })
  }, [refresh, putRoom, admin, onMe])

  const run = useCallback(async <T,>(action: () => Promise<T>, failure = 'Something went wrong') => {
    try {
      return await action()
    } catch (e) {
      toast.error((e as Error).message || failure)
      return undefined
    }
  }, [])

  const value = useMemo(
    () => ({ me, rooms, groups, status, boilerOverride, setBoilerOverride, loading, live, latestActivity, boostTarget, setBoostTarget, refresh, putRoom, setStatus, putGroup, removeGroup, run }),
    [me, rooms, groups, status, boilerOverride, loading, live, latestActivity, boostTarget, refresh, putRoom, putGroup, removeGroup, run],
  )
  return <HomeContext.Provider value={value}>{children}</HomeContext.Provider>
}

export function useHome(): Home {
  const home = useContext(HomeContext)
  if (!home) throw new Error('useHome outside HomeProvider')
  return home
}
