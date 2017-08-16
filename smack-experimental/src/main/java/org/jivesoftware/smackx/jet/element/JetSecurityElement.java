/**
 *
 * Copyright 2017 Paul Schaub
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jivesoftware.smackx.jet.element;

import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.util.XmlStringBuilder;
import org.jivesoftware.smackx.jet.component.JetSecurity;
import org.jivesoftware.smackx.jingle.element.JingleContentSecurityElement;

/**
 * Implementation of the Jingle security element as specified in XEP-XXXX (Jingle Encrypted Transfers).
 * <jingle>
 *     <content>
 *         <description/>
 *         <transport/>
 *         <security/> <- You are here.
 *     </content>
 * </jingle>
 */
public class JetSecurityElement extends JingleContentSecurityElement {
    public static final String ATTR_NAME = "name";
    public static final String ATTR_TYPE = "type";
    public static final String ATTR_CIPHER = "cipher";

    private final ExtensionElement child;
    private final String name;
    private final String cipherName;

    public JetSecurityElement(String name, String cipherName, ExtensionElement child) {
        this.name = name;
        this.child = child;
        this.cipherName = cipherName;
    }

    @Override
    public CharSequence toXML() {
        XmlStringBuilder xml = new XmlStringBuilder(this);
        xml.attribute(ATTR_NAME, name)
                .attribute(ATTR_CIPHER, cipherName)
                .attribute(ATTR_TYPE, child.getNamespace());
        xml.rightAngleBracket();
        xml.element(child);
        xml.closeElement(this);
        return xml;
    }

    @Override
    public String getNamespace() {
        return JetSecurity.NAMESPACE;
    }

    public String getMethodNamespace() {
        return child.getNamespace();
    }

    public ExtensionElement getChild() {
        return child;
    }

    public String getContentName() {
        return name;
    }

    public String getCipherName() {
        return cipherName;
    }
}
