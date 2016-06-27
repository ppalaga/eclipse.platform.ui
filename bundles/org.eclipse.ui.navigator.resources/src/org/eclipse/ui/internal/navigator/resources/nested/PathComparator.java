/*******************************************************************************
 * Copyright (c) 2015 Red Hat Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Mickael Istria (Red Hat Inc.) - initial API and implementation
 ******************************************************************************/
package org.eclipse.ui.internal.navigator.resources.nested;

import java.util.Comparator;

import org.eclipse.core.runtime.IPath;


/**
 * @since 3.3
 *
 */
public class PathComparator implements Comparator<IPath> {

	@Override
	public int compare(IPath arg0, IPath arg1) {
		for (int i = 0; i < Math.min(arg0.segmentCount(), arg1.segmentCount()); i++) {
			int res = arg0.segment(i).compareTo(arg1.segment(i));
			if (res != 0) {
				return res;
			}
		}
		return arg0.segmentCount() - arg1.segmentCount();
	}

}
