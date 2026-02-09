# API CONTRACT

POST /craftmate/vision
{
  player_id,
  image_b64,
  reason
}

Returns:
{ ok, spoken_line? }
