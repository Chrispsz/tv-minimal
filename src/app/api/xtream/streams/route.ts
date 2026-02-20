import { NextRequest, NextResponse } from 'next/server'

export async function POST(request: NextRequest) {
  try {
    const { host, username, password, type = 'live', categoryId } = await request.json()

    if (!host || !username || !password) {
      return NextResponse.json({ error: 'Credenciais incompletas' }, { status: 400 })
    }

    const fullHost = host.includes(':') ? host : `${host}:8080`
    const serverUrl = `http://${fullHost}`

    // Buscar streams
    let action = type === 'live' ? 'get_live_streams' : 'get_vod_streams'
    let url = `${serverUrl}/player_api.php?username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}&action=${action}`
    
    if (categoryId) {
      url += `&category_id=${categoryId}`
    }

    console.log('[Xtream] Fetching streams:', type, categoryId || 'all')

    const response = await fetch(url, {
      method: 'GET',
      headers: {
        'User-Agent': 'IPTVPlayer/1.0',
      },
      signal: AbortSignal.timeout(30000)
    })

    if (!response.ok) {
      return NextResponse.json({ error: `Erro HTTP: ${response.status}` }, { status: response.status })
    }

    const streams = await response.json()

    // Processar streams
    const processedStreams = (Array.isArray(streams) ? streams : []).map((stream: any) => ({
      streamId: stream.stream_id,
      name: stream.name,
      icon: stream.stream_icon,
      categoryId: stream.category_id,
      epgChannelId: stream.epg_channel_id,
      added: stream.added,
      // Para VOD
      rating: stream.rating,
      releaseDate: stream.releaseDate,
    }))

    return NextResponse.json({
      serverUrl,
      streams: processedStreams,
      total: processedStreams.length,
      type
    })
  } catch (error: any) {
    console.error('[Xtream] Streams error:', error)
    return NextResponse.json({ error: error.message }, { status: 500 })
  }
}
