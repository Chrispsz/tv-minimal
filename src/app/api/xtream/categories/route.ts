import { NextRequest, NextResponse } from 'next/server'

export async function POST(request: NextRequest) {
  try {
    const { host, username, password, type = 'live' } = await request.json()

    if (!host || !username || !password) {
      return NextResponse.json({ error: 'Credenciais incompletas' }, { status: 400 })
    }

    const fullHost = host.includes(':') ? host : `${host}:8080`
    const serverUrl = `http://${fullHost}`

    // Buscar categorias
    const action = type === 'live' ? 'get_live_categories' : 'get_vod_categories'
    const url = `${serverUrl}/player_api.php?username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}&action=${action}`

    console.log('[Xtream] Fetching categories:', type)

    const response = await fetch(url, {
      method: 'GET',
      headers: {
        'User-Agent': 'IPTVPlayer/1.0',
      },
      signal: AbortSignal.timeout(15000)
    })

    if (!response.ok) {
      return NextResponse.json({ error: `Erro HTTP: ${response.status}` }, { status: response.status })
    }

    const categories = await response.json()

    return NextResponse.json({
      serverUrl,
      categories: Array.isArray(categories) ? categories : [],
      total: Array.isArray(categories) ? categories.length : 0
    })
  } catch (error: any) {
    console.error('[Xtream] Categories error:', error)
    return NextResponse.json({ error: error.message }, { status: 500 })
  }
}
