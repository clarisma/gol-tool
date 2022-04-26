package com.geodesk.gol.compiler;

public class UsageScores 
{
	/**
	 * Basic usage assigned to any local feature (a feature
	 * which is indexed in the spatial index)
	 */
	public static final float BASE_FEATURE_SCORE = 100;
	/**
	 * Percentage of a way's score that is awarded to 
	 * a node that belongs to it.
	 */
	public static final float WAYNODE_RATIO = 0.6f;
	/**
	 * A per-member bonus score that is awarded to a relation.
	 */
	public static final float RELATION_MEMBER_BONUS_SCORE = 10;
	/**
	 * Usage awarded to a feature for each membership in a
	 * relation.
	 */
	public static final float RELATION_REFERENCE_SCORE = 75;
	/**
	 * Percentage of a feature's score that is awarded to its
	 * tag table (the percentage reflects the probability that
	 * the tag table is accessed if the feature is accessed).
	 */
	public static final float TAGTABLE_RATIO = 0.9f;
	/**
	 * Percentage of a tag table's score that is awarded to
	 * a string which serves as a key in the tag table
	 */
	public static final float KEY_STRING_RATIO = 0.5f;
	/**
	 * Percentage of a tag table's score that is awarded to
	 * a string that represent a regular value. Awarded for
	 * each occurrence of the value in the table.
	 */
	public static final float VALUE_STRING_RATIO = 0.1f;
	/**
	 * Percentage of a tag table's score that is awarded to
	 * a string that represent a more frequently used value 
	 * (such as for a "name" tag). Awarded for each occurrence 
	 * of the value in the table.
	 */
	public static final float SPECIAL_VALUE_STRING_RATIO = 0.4f;
	/**
	 * Percentage of a feature's score that is awarded to its
	 * relation table.
	 */
	public static final float RELATIONTABLE_RATIO = 0.15f;
	/**
	 * Percentage of a relation's score that is awarded to a
	 * local role used by its member (applied per member).
	 */
	public static final float ROLE_STRING_RATIO = 0.25f;
}
