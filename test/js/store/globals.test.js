import { describe, expect, it } from 'vitest'
import { getters, state as createState } from '@/store/globals'

function stateWithDownloads(itemDownloads) {
  return { ...createState(), itemDownloads }
}

describe('globals getDownloadItem', () => {
  const bookDownload = { id: 'dl-1', libraryItemId: 'li-book', episodeId: null }
  const episodeDownload = { id: 'dl-2', libraryItemId: 'li-podcast', episodeId: 'ep-2' }
  const getDownloadItem = getters.getDownloadItem(stateWithDownloads([bookDownload, episodeDownload]))

  it('finds a download by library item id', () => {
    expect(getDownloadItem('li-book')).toBe(bookDownload)
  })

  it('matches the episode when an episode id is given', () => {
    expect(getDownloadItem('li-podcast', 'ep-2')).toBe(episodeDownload)
  })

  it('returns undefined when the episode does not match', () => {
    expect(getDownloadItem('li-podcast', 'ep-other')).toBeUndefined()
  })

  it('returns undefined when nothing is downloading', () => {
    expect(getters.getDownloadItem(stateWithDownloads([]))('li-book')).toBeUndefined()
  })
})
