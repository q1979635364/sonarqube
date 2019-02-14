/*
 * SonarQube
 * Copyright (C) 2009-2019 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
import * as React from 'react';
import { sortBy } from 'lodash';
import { Link } from 'react-router';
import OrganizationsShortListItem from './OrganizationsShortListItem';
import { translate, translateWithParameters } from '../../../helpers/l10n';

export interface Props {
  organizations: T.Organization[];
  skipOnboarding: () => void;
}

export default function OrganizationsShortList({ organizations, skipOnboarding }: Props) {
  if (organizations.length === 0) {
    return null;
  }

  const organizationsShown = sortBy(organizations, organization =>
    organization.name.toLocaleLowerCase()
  ).slice(0, 3);

  return (
    <div>
      <ul className="account-projects-list">
        {organizationsShown.map(organization => (
          <li key={organization.key}>
            <OrganizationsShortListItem
              organization={organization}
              skipOnboarding={skipOnboarding}
            />
          </li>
        ))}
      </ul>
      <div className="big-spacer-top">
        <span className="big-spacer-right">
          {translateWithParameters('x_of_y_shown', organizationsShown.length, organizations.length)}
        </span>
        {organizations.length > 3 && (
          <Link className="small" onClick={skipOnboarding} to="/account/organizations">
            {translate('see_all')}
          </Link>
        )}
      </div>
    </div>
  );
}
