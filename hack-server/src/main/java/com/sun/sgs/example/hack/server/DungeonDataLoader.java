/*
 * This work is hereby released into the Public Domain. 
 * To view a copy of the public domain dedication, visit 
 * http://creativecommons.org/licenses/publicdomain/ or send 
 * a letter to Creative Commons, 171 Second Street, Suite 300, 
 * San Francisco, California, 94105, USA.
 */

package com.sun.sgs.example.hack.server;

import com.sun.sgs.app.AppContext;
import com.sun.sgs.app.DataManager;
import com.sun.sgs.app.ManagedReference;

import com.sun.sgs.example.hack.server.level.DungeonFactory;

import java.awt.Image;

import java.awt.image.BufferedImage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.StreamTokenizer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import java.util.logging.Logger;

import javax.imageio.ImageIO;


/**
 * This utility class provides static interfaces for loading the simple
 * dungeon and sprite file formats used to setup an app.
 */
public class DungeonDataLoader
{

    private static final Logger logger = 
	Logger.getLogger(DungeonDataLoader.class.getName());

    /**
     * Private helper that sets up a StreamTokenizer from a file.
     */
    private static StreamTokenizer getTokenizer(String filename)
        throws IOException
    {
        InputStreamReader input =
            new InputStreamReader(new FileInputStream(filename));
        StreamTokenizer stok = new StreamTokenizer(input);

        stok.commentChar('#');
        stok.quoteChar('\"');
        stok.eolIsSignificant(false);

        return stok;
    }

    /**
     * This will load the given file, and use its contents to load all
     * the dungeons and sprite maps, handling all registration and task
     * installation required.
     *
     * @param filename the file to start loading
     * @param lobbyRef a reference to the lobby
     * @param gcmRef a reference to the game change manager
     */
    public static void setupDungeons(String root, String filename, Lobby lobby,
                                     GameChangeManager gcm)
        throws IOException
    {
        StreamTokenizer stok = getTokenizer(root + filename);

        HashMap<Integer,Set<Integer>> impMap =
            new HashMap<Integer,Set<Integer>>();
        HashSet<Dungeon> dungeons = new HashSet<Dungeon>();

        // load the sprite maps and dungeons
        while (stok.nextToken() != StreamTokenizer.TT_EOF) {
            if (stok.sval.equals("SpriteMap")) {
                logger.fine("loading sprite map");
                stok.nextToken();
                int mapId = (int)(stok.nval);
                stok.nextToken();
                int spriteSize = (int)(stok.nval);
                stok.nextToken();
                String smFile = root + stok.sval;
                AppContext.getDataManager().
                    setBinding(SpriteMap.NAME_PREFIX + mapId,
                               loadSpriteMap(smFile, spriteSize));
                impMap.put(mapId, getImpassableSet(smFile + ".imp"));
            } else if (stok.sval.equals("Dungeon")) {
                logger.fine("loading dungeon");
                stok.nextToken();
                String name = stok.sval;
                stok.nextToken();
                int spriteMapId = (int)(stok.nval);
                stok.nextToken();
                String dFile = root + stok.sval;
                StreamTokenizer dungeonStream = getTokenizer(dFile);
                Set<Integer> impSet = impMap.get(spriteMapId);
                GameConnector gc =
                    DungeonFactory.loadDungeon(dungeonStream, name, impSet,
                                               lobby/*,ag*/);
                dungeons.add(new Dungeon(name, spriteMapId, gc));
            } else {
                logger.warning("Unknown type: " + stok.sval + " on line "
                                   + stok.lineno());
            }
        }

        // register each dungeon
        DataManager dataManager = AppContext.getDataManager();
        for (Dungeon dungeon : dungeons) {
            logger.fine("Registering dungeon: " + dungeon.getName());
            dataManager.setBinding(Game.NAME_PREFIX + dungeon.getName(),
                                   dungeon);
            gcm.notifyGameAdded(dungeon.getName());
        }

        logger.info("finished loading dungeon data and image files");
    }

    

    /**
     * Loads a sprite map file.
     *
     * @param filename the graphics file containing the sprites
     * @param spriteSize the size of each sprite
     *
     * @return a container for the sprites
     */
    public static SpriteMap loadSpriteMap(String filename, int spriteSize)
        throws IOException
    {
        logger.finer("Trying to read images from: " + filename);

        // open the file into a single image
        BufferedImage image = ImageIO.read(new File(filename));

        // create the map
        HashMap<Integer,byte[]> spriteMap = new HashMap<Integer,byte[]>();

        // now split up the image into its component sprites

        // REMINDER: In the event that we don't control the image
        //           data, this method should check that the images
        //           are the right dimension
        int identifier = 1;
        int totalSize = 0;
        for (int y = 0; y < image.getHeight() / spriteSize; y++) {
            for (int x = 0; x < image.getWidth() / spriteSize; x++) {
                BufferedImage sprite =
                    image.getSubimage(x * spriteSize, y * spriteSize,
                                      spriteSize, spriteSize);
                byte [] bytes = imageToBytes(sprite);
                spriteMap.put(identifier++, bytes);
                totalSize += bytes.length;
            }
        }

        logger.finer("image bytes read: " + totalSize);

        return new SpriteMap(spriteSize, spriteMap);
    }

    /**
     * Private helper used to turn each image into bytes.
     */
    private static byte [] imageToBytes(BufferedImage image)
        throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (! ImageIO.write(image, "png", out))
            logger.warning("FAILED TO GET BYTES!!");
        return out.toByteArray();
    }

    /**
     * Returns the set of impassible tiles for the dungeon stored in
     * the provided file.
     */
    public static Set<Integer> getImpassableSet(String filename)
        throws IOException
    {
        StreamTokenizer stok = getTokenizer(filename);
        HashSet<Integer> impassableSet = new HashSet<Integer>();

        while (stok.nextToken() != StreamTokenizer.TT_EOF)
            impassableSet.add((int)(stok.nval));

        return impassableSet;
    }

}