package com.worldheatmap;

/**
 * Choices for which heatmap the overlays show, i.e. every HeatmapType plus NONE.
 * Kept separate from HeatmapType so NONE doesn't show up as a choice in the enabled heatmaps list.
 * Constant names match HeatmapType's, so a stored overlayHeatmapType value maps straight across.
 */
public enum OverlayHeatmap
{
	NONE,
	TYPE_A,
	TYPE_B,
	XP_GAINED,
	TELEPORT_PATHS,
	TELEPORTED_TO,
	TELEPORTED_FROM,
	LOOT_VALUE,
	PLACES_SPOKEN_AT,
	RANDOM_EVENT_SPAWNS,
	DEATHS,
	NPC_DEATHS,
	BOB_THE_CAT_SIGHTING,
	NPC_DAMAGE_TAKEN,
	NPC_DAMAGE_GIVEN,
	PVP_DAMAGE_GIVEN,
	PVP_DAMAGE_TAKEN,
	WORLD_HOPS,
	LOGIN_LOGOUT,
	TRADES;

	/**
	 * @return the corresponding heatmap type, or null for NONE
	 */
	public Heatmap.HeatmapType getHeatmapType()
	{
		return this == NONE ? null : Heatmap.HeatmapType.valueOf(name());
	}

	public static OverlayHeatmap of(Heatmap.HeatmapType type)
	{
		return type == null ? NONE : valueOf(type.name());
	}

	@Override
	public String toString()
	{
		return this == NONE ? "None" : getHeatmapType().toString();
	}
}
