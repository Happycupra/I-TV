package nl.vanvrouwerff.iptv.data.repo

/** A source without EPG support cannot be repaired by background retrying. */
internal class EpgUnavailableException(message: String) : IllegalStateException(message)
