package org.bukkit;

import java.util.Random;
import org.bukkit.block.Block;

/**
 * An environment generator is responsible for generating a small area of blocks.
 * For example, generating lightstone inside the nether
 */
public interface BlockGenerator {
    /**
     * Generates an area of blocks at or around the given source block
     *
     * @param random The random generator to use
     * @param source The changed block which triggered this generation
     * @return An array of all generated or otherwise changed blocks
     */
    public Block[] generate(Random random, Block source);
}
