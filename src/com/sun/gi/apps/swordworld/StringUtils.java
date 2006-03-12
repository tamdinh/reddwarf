/*
 * Copyright © 2006 Sun Microsystems, Inc., 4150 Network Circle, Santa
 * Clara, California 95054, U.S.A. All rights reserved.
 * 
 * Sun Microsystems, Inc. has intellectual property rights relating to
 * technology embodied in the product that is described in this
 * document. In particular, and without limitation, these intellectual
 * property rights may include one or more of the U.S. patents listed at
 * http://www.sun.com/patents and one or more additional patents or
 * pending patent applications in the U.S. and in other countries.
 * 
 * U.S. Government Rights - Commercial software. Government users are
 * subject to the Sun Microsystems, Inc. standard license agreement and
 * applicable provisions of the FAR and its supplements.
 * 
 * Use is subject to license terms.
 * 
 * This distribution may include materials developed by third parties.
 * 
 * Sun, Sun Microsystems, the Sun logo and Java are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other
 * countries.
 * 
 * This product is covered and controlled by U.S. Export Control laws
 * and may be subject to the export or import laws in other countries.
 * Nuclear, missile, chemical biological weapons or nuclear maritime end
 * uses or end users, whether direct or indirect, are strictly
 * prohibited. Export or reexport to countries subject to U.S. embargo
 * or to entities identified on U.S. export exclusion lists, including,
 * but not limited to, the denied persons and specially designated
 * nationals lists is strictly prohibited.
 * 
 * Copyright © 2006 Sun Microsystems, Inc., 4150 Network Circle, Santa
 * Clara, California 95054, Etats-Unis. Tous droits réservés.
 * 
 * Sun Microsystems, Inc. détient les droits de propriété intellectuels
 * relatifs à la technologie incorporée dans le produit qui est décrit
 * dans ce document. En particulier, et ce sans limitation, ces droits
 * de propriété intellectuelle peuvent inclure un ou plus des brevets
 * américains listés à l'adresse http://www.sun.com/patents et un ou les
 * brevets supplémentaires ou les applications de brevet en attente aux
 * Etats - Unis et dans les autres pays.
 * 
 * L'utilisation est soumise aux termes de la Licence.
 * 
 * Cette distribution peut comprendre des composants développés par des
 * tierces parties.
 * 
 * Sun, Sun Microsystems, le logo Sun et Java sont des marques de
 * fabrique ou des marques déposées de Sun Microsystems, Inc. aux
 * Etats-Unis et dans d'autres pays.
 * 
 * Ce produit est soumis à la législation américaine en matière de
 * contrôle des exportations et peut être soumis à la règlementation en
 * vigueur dans d'autres pays dans le domaine des exportations et
 * importations. Les utilisations, ou utilisateurs finaux, pour des
 * armes nucléaires,des missiles, des armes biologiques et chimiques ou
 * du nucléaire maritime, directement ou indirectement, sont strictement
 * interdites. Les exportations ou réexportations vers les pays sous
 * embargo américain, ou vers des entités figurant sur les listes
 * d'exclusion d'exportation américaines, y compris, mais de manière non
 * exhaustive, la liste de personnes qui font objet d'un ordre de ne pas
 * participer, d'une façon directe ou indirecte, aux exportations des
 * produits ou des services qui sont régis par la législation américaine
 * en matière de contrôle des exportations et la liste de ressortissants
 * spécifiquement désignés, sont rigoureusement interdites.
 */

package com.sun.gi.apps.swordworld;

import java.util.ArrayList;

/**
 * 
 *
 * <p>Title: StringUtils.java</p>
 * <p>Description: </p>
 * <p>  This is a class  that holds some string parsing utilities
 * that are used by the Player object to break up the input string from the
 * client into understandable words.
 * <p>Company: Sun Microsystems, Inc</p>
 * @author Jeff Kesselman
 * @version 1.0
 */
public abstract class StringUtils {

    public static String[] explode(String str, String delim) {
        return explode(str, delim, true);
    }

    /**
     * This class breaks a strign up into sub-strings seperated by the
     * passed in delimiter.  If trim is true then it takes any delimiters 
     * off the start and end of the string before breaking it up.
     * @param str The string to break up
     * @param delim The string to be used as a delimiter (most commonly " ")
     * @param trim Whether to remove elading and trailing delimeters or not
     * @return the list of sub-strings
     */
    public static String[] explode(String str, String delim, boolean trim) {
        ArrayList<String> plist = new ArrayList<String>();
        if (str.indexOf(delim) == -1){
        	return new String[]{str};
        }
        while (str.length() > 0) {
            int delimpos = str.indexOf(delim);
            
            String leftString = str.substring(0, delimpos);
            if (leftString.length() > 0) {
                if (trim) {
                    leftString = leftString.trim();
                }
                plist.add(leftString);
            }
            str = str.substring(delimpos + delim.length());
        }
        String[] retarray = new String[plist.size()];
        return plist.toArray(retarray);
    }

    public static String bytesToHex(byte[] bytes, int start, int length) {
        StringBuffer buff = new StringBuffer();
        for (int i = 0; i < length; i++) {
            buff.append(byteToHex(bytes[i + start]));
        }
        return buff.toString();
    }

    public static String bytesToHex(byte[] bytes) {
        return bytesToHex(bytes, 0, bytes.length);
    }

    private static String[] hexDigits = { "A", "B", "C", "D", "E", "F" };

    public static String byteToHex(byte b) {
        return halfByteToHex((byte) (b >> 4)) + halfByteToHex((byte) (b & 0xF));
    }

    private static String halfByteToHex(byte b) {
        b &= (byte) 0xF;
        if (b < 10)
            return String.valueOf((int) b);
        return hexDigits[b - 10];
    }

    public static String bytesToQuads(byte[] bytes) {
        StringBuffer buff = new StringBuffer();
        for (int i = 0; i < bytes.length; i++) {
            buff.append(String.valueOf((int) (bytes[i])));
            if (i + 1 < bytes.length) {
                buff.append(":");
            }
        }
        return buff.toString();
    }

    public static String bytesToHex(byte[] from, int i) {
        return bytesToHex(from, i, from.length - i);
    }
}