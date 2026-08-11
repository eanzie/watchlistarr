package model

/** One page of a Sonarr/Radarr `.../paged` response.
  *
  * Only the fields watchlistarr needs are modelled; the endpoints also return page, pageSize,
  * sortKey and sortDirection.
  */
case class ArrPage[T](records: List[T], totalRecords: Int)
