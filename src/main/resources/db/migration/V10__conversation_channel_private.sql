-- Whether the channel of a conversation is private (true, DMs included), public (false) or unknown (null).
-- Only conversations known to be in a public channel may have their question text sent to a model or quoted.
ALTER TABLE conversation ADD COLUMN channel_private BOOLEAN;
