/*
 * Copyright (C) 2015 VersoBit Ltd
 *
 * This file is part of Weather Doge.
 *
 * Weather Doge is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Weather Doge is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Weather Doge.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.versobit.weatherdoge;

import android.location.Location;

interface LocationReceiver {

    void onConnected();

    void onLocation(Location location);

}
