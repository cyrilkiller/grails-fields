/*
 * Copyright 2012 Rob Fletcher
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

package grails.plugin.formfields

import grails.util.GrailsNameUtils
import org.apache.commons.lang.StringUtils
import org.codehaus.groovy.grails.plugins.support.aware.GrailsApplicationAware
import org.codehaus.groovy.grails.scaffolding.DomainClassPropertyComparator
import static FormFieldsTemplateService.toPropertyNameFormat
import org.codehaus.groovy.grails.commons.*
import static org.codehaus.groovy.grails.commons.GrailsClassUtils.getStaticPropertyValue
import org.codehaus.groovy.grails.web.pages.GroovyPage

class FormFieldsTagLib implements GrailsApplicationAware {

	static final namespace = 'f'
	static final String BEAN_PAGE_SCOPE_VARIABLE = 'f:with:bean'

	FormFieldsTemplateService formFieldsTemplateService
	GrailsApplication grailsApplication
	BeanPropertyAccessorFactory beanPropertyAccessorFactory

	Closure with = { attrs, body ->
		if (!attrs.bean) throwTagError("Tag [with] is missing required attribute [bean]")
		def bean = resolveBean(attrs.bean)
		try {
			pageScope.variables[BEAN_PAGE_SCOPE_VARIABLE] = bean
			out << body()
		} finally {
			pageScope.variables.remove(BEAN_PAGE_SCOPE_VARIABLE)
		}
	}

	Closure all = { attrs ->
		if (!attrs.bean) throwTagError("Tag [all] is missing required attribute [bean]")
		def bean = resolveBean(attrs.bean)
		def domainClass = resolveDomainClass(bean)
		def fieldTemplateName = attrs.template ?: 'field'

		if (domainClass) {
			for (property in resolvePersistentProperties(domainClass, attrs)) {
				if (property.embedded) {
					out << '<fieldset class="' << toPropertyNameFormat(property.type) << '">'
					out << '<legend>' << GrailsNameUtils.getNaturalName(property.type.simpleName) << '</legend>'
					for (embeddedProp in resolvePersistentProperties(property.component, attrs)) {
						def propertyPath = "${property.name}.${embeddedProp.name}"
						out << field(bean: bean, property: propertyPath, template: fieldTemplateName)
					}
					out << '</fieldset>'
				} else {
					out << field(bean: bean, property: property.name, template: fieldTemplateName)
				}
			}
		} else {
			// TODO: handle POGOs
		}
	}

	Closure field = { attrs, body ->
		if (attrs.containsKey('bean') && !attrs.bean) throwTagError("Tag [field] requires a non-null value for attribute [bean]")
		if (!attrs.property) throwTagError("Tag [field] is missing required attribute [property]")

		def templateName = attrs.remove('template') ?: 'field'
		def bean = attrs.remove('bean')
		def property = attrs.remove('property')

		def propertyAccessor = resolveProperty(bean, property)
		def model = buildModel(propertyAccessor, attrs)

		// any remaining attrs at this point are 'extras'
		model += attrs

		if (hasBody(body)) {
			model.widget = body(model)
		} else {
			model.widget = renderWidget('input', propertyAccessor, model, attrs)
		}

		def template = formFieldsTemplateService.findTemplate(propertyAccessor, templateName)
		out << render(template: template.path, plugin: template.plugin, model: model)
	}

	Closure widget = { String name, attrs ->
		if (!attrs.bean) throwTagError("Tag [$name] is missing required attribute [bean]")
		if (!attrs.property) throwTagError("Tag [$name] is missing required attribute [property]")

		def bean = attrs.remove('bean')
		def property = attrs.remove('property')

		def propertyAccessor = resolveProperty(bean, property)
		def model = buildModel(propertyAccessor, attrs)
		out << renderWidget(name, propertyAccessor, model, attrs)
	}

	Closure input = widget.curry("input")
	Closure show = widget.curry("show")

	private BeanPropertyAccessor resolveProperty(beanAttribute, String propertyPath) {
		def bean = resolveBean(beanAttribute)
		def propertyAccessor = beanPropertyAccessorFactory.accessorFor(bean, propertyPath)
		return propertyAccessor
	}

	private Map buildModel(BeanPropertyAccessor propertyAccessor, Map attrs) {
		def valueOverride = attrs.remove('value')
		def valueDefault = attrs.remove('default')
		[
				bean: propertyAccessor.rootBean,
				property: propertyAccessor.pathFromRoot,
				type: propertyAccessor.propertyType,
				label: resolveLabelText(propertyAccessor, attrs),
				value: valueOverride ?: propertyAccessor.value ?: valueDefault,
				constraints: propertyAccessor.constraints,
				persistentProperty: propertyAccessor.persistentProperty,
				errors: propertyAccessor.errors.collect { message(error: it) },
				required: attrs.containsKey("required") ? Boolean.valueOf(attrs.remove('required')) : propertyAccessor.required,
				invalid: attrs.containsKey("invalid") ? Boolean.valueOf(attrs.remove('invalid')) : propertyAccessor.invalid,
		]
	}

	private String renderWidget(String name, BeanPropertyAccessor propertyAccessor, Map model, Map attrs) {
		def template = formFieldsTemplateService.findTemplate(propertyAccessor, name)
		if (template) {
			return render(template: template.path, plugin: template.plugin, model: model + attrs)
		} else {
			return renderDefaultInput(model, attrs)
		}
	}

	private Object resolveBean(beanAttribute) {
		def bean = pageScope.variables[BEAN_PAGE_SCOPE_VARIABLE]
		if (!bean) {
			bean = pageScope.variables[beanAttribute] ?: beanAttribute
		}
		bean
	}

	private GrailsDomainClass resolveDomainClass(bean) {
		resolveDomainClass(bean.getClass())
	}

	private GrailsDomainClass resolveDomainClass(Class beanClass) {
		grailsApplication.getDomainClass(beanClass.name)
	}

	private List<GrailsDomainClassProperty> resolvePersistentProperties(GrailsDomainClass domainClass, attrs) {
		def properties = domainClass.persistentProperties as List

		def blacklist = attrs.except?.tokenize(',')*.trim() ?: []
		blacklist << 'dateCreated' << 'lastUpdated'
		def scaffoldProp = getStaticPropertyValue(domainClass.clazz, 'scaffold')
		if (scaffoldProp) {
			blacklist.addAll(scaffoldProp.exclude)
		}
		properties = properties.findAll { !(it.name in blacklist) }

		Collections.sort(properties, new DomainClassPropertyComparator(domainClass))
		properties
	}

	private boolean hasBody(Closure body) {
		return !body.is(GroovyPage.EMPTY_BODY_CLOSURE)
	}

	private String resolveLabelText(BeanPropertyAccessor propertyAccessor, Map attrs) {
		def label = attrs.remove('label')
		def labelKey = attrs.remove('labelKey')
		if (!label && labelKey) {
			label = message(code: labelKey)
		}
		if (!label && propertyAccessor.labelKey) {
			label = message(code: propertyAccessor.labelKey, default: propertyAccessor.defaultLabel)
		}
		if (!label) {
			label = propertyAccessor.defaultLabel
		}
		label
	}

	private String renderDefaultInput(Map attrs, Map extraAttrs = [:]) {
		def model = [:]
		model.name = attrs.property
		model.value = attrs.value
		if (attrs.required) model.required = "" // TODO: configurable how this gets output? Some people prefer required="required"
		if (attrs.invalid) model.invalid = ""
		if (!attrs.constraints.editable) model.readonly = ""

		if (attrs.type in [String, null]) {
			return renderStringInput(model, attrs, extraAttrs)
		} else if (attrs.type in [boolean, Boolean]) {
			return g.checkBox(model + extraAttrs)
		} else if (attrs.type.isPrimitive() || attrs.type in Number) {
			return renderNumericInput(model, attrs, extraAttrs)
		} else if (attrs.type in URL) {
			return g.field(model + [type: "url"] + extraAttrs)
		} else if (attrs.type.isEnum()) {
			model.from = attrs.type.values()
			if (!attrs.required) model.noSelection = ["": ""]
			return g.select(model + extraAttrs)
		} else if (attrs.persistentProperty.oneToOne || attrs.persistentProperty.manyToOne || attrs.persistentProperty.manyToMany) {
			return renderAssociationInput(model, attrs, extraAttrs)
		} else if (attrs.persistentProperty.oneToMany) {
			return renderOneToManyInput(model, attrs)
		} else if (attrs.type in [Date, Calendar, java.sql.Date, java.sql.Time]) {
			return renderDateTimeInput(model, attrs, extraAttrs)
		} else if (attrs.type in [byte[], Byte[]]) {
			return g.field(model + [type: "file"] + extraAttrs)
		} else if (attrs.type in [TimeZone, Currency, Locale]) {
			if (!attrs.required) model.noSelection = ["": ""]
			return g."${StringUtils.uncapitalize(attrs.type.simpleName)}Select"(model + extraAttrs)
		} else {
			return null
		}
	}

	private String renderDateTimeInput(Map model, Map attrs, Map extraAttrs) {
		model.precision = attrs.type == java.sql.Time ? "minute" : "day"
		if (!attrs.required) {
			model.noSelection = ["": ""]
			model.default = "none"
		}
		return g.datePicker(model + extraAttrs)
	}

	private String renderStringInput(Map model, Map attrs, Map extraAttrs) {
		if (attrs.constraints.inList) {
			model.from = attrs.constraints.inList
			if (!attrs.required) model.noSelection = ["": ""]
			return g.select(model)
		} else if (attrs.constraints.password) model.type = "password"
		else if (attrs.constraints.email) model.type = "email"
		else if (attrs.constraints.url) model.type = "url"
		else model.type = "text"

		if (attrs.constraints.matches) model.pattern = attrs.constraints.matches
		if (attrs.constraints.maxSize) model.maxlength = attrs.constraints.maxSize

		return g.field(model + extraAttrs)
	}

	private String renderNumericInput(Map model, Map attrs, Map extraAttrs) {
		if (attrs.constraints.inList) {
			model.from = attrs.constraints.inList
			if (!attrs.required) model.noSelection = ["": ""]
			return g.select(model)
		} else if (attrs.constraints.range) {
			model.type = "range"
			model.min = attrs.constraints.range.from
			model.max = attrs.constraints.range.to
		} else {
			model.type = "number"
			if (attrs.constraints.min != null) model.min = attrs.constraints.min
			if (attrs.constraints.max != null) model.max = attrs.constraints.max
		}
		return g.field(model + extraAttrs)
	}

	private String renderAssociationInput(Map model, Map attrs, Map extraAttrs) {
		model.name = "${attrs.property}.id"
		model.id = attrs.property
		model.from = attrs.persistentProperty.referencedPropertyType.list()
		model.optionKey = "id" // TODO: handle alternate id names
		if (attrs.persistentProperty.manyToMany) {
			model.multiple = ""
			model.value = attrs.value*.id
		} else {
			if (!attrs.required) model.noSelection = ["null": ""]
			model.value = attrs.value?.id
		}
		return g.select(model + extraAttrs)
	}

	private String renderOneToManyInput(Map model, Map attrs) {
		def buffer = new StringBuilder()
		buffer << '<ul>'
		def referencedDomainClass = attrs.persistentProperty.referencedDomainClass
		def controllerName = referencedDomainClass.propertyName
		model.value.each {
			buffer << '<li>'
			buffer << g.link(controller: controllerName, action: "show", id: it.id, it.toString().encodeAsHTML())
			buffer << '</li>'
		}
		buffer << '</ul>'
		def referencedTypeLabel = message(code: "${referencedDomainClass.propertyName}.label", default: referencedDomainClass.shortName)
		def addLabel = g.message(code: 'default.add.label', args: [referencedTypeLabel])
		buffer << g.link(controller: controllerName, action: "create", params: [("${attrs.beanDomainClass.propertyName}.id".toString()): attrs.bean.id], addLabel)
		buffer as String
	}

}