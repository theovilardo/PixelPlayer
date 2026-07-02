/* -*- Mode: C; indent-tabs-mode:t ; c-basic-offset:8 -*- */
/*
 * USB descriptor handling functions for libusb
 * Copyright © 2007 Daniel Drake <dsd@gentoo.org>
 * Copyright © 2001 Johannes Erdfelt <johannes@erdfelt.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

#include "libusbi.h"

#include <string.h>

#define DESC_HEADER_LENGTH	2

/** @defgroup libusb_desc USB descriptors
 * This page details how to examine the various standard USB descriptors
 * for detected devices
 */

static inline uint16_t ReadLittleEndian16(const uint8_t p[2])
{
	return (uint16_t)((uint16_t)p[1] << 8 |
			  (uint16_t)p[0]);
}

static inline uint32_t ReadLittleEndian32(const uint8_t p[4])
{
	return ((uint32_t)p[3] << 24 |
			(uint32_t)p[2] << 16 |
			(uint32_t)p[1] << 8 |
			(uint32_t)p[0]);
}

static void clear_endpoint(struct libusb_endpoint_descriptor *endpoint)
{
	free((void *)endpoint->extra);
}

static int parse_endpoint(struct libusb_context *ctx,
	struct libusb_endpoint_descriptor *endpoint, const uint8_t *buffer, int size)
{
	const struct usbi_descriptor_header *header;
	const uint8_t *begin;
	void *extra;
	int parsed = 0;

	if (size < DESC_HEADER_LENGTH) {
		usbi_err(ctx, "short endpoint descriptor read %d/%d",
			 size, DESC_HEADER_LENGTH);
		return LIBUSB_ERROR_IO;
	}

	header = (const struct usbi_descriptor_header *)buffer;
	if (header->bDescriptorType != LIBUSB_DT_ENDPOINT) {
		usbi_err(ctx, "unexpected descriptor 0x%x (expected 0x%x)",
			header->bDescriptorType, LIBUSB_DT_ENDPOINT);
		return parsed;
	} else if (header->bLength < LIBUSB_DT_ENDPOINT_SIZE) {
		usbi_err(ctx, "invalid endpoint bLength (%u)", header->bLength);
		return LIBUSB_ERROR_IO;
	} else if (header->bLength > size) {
		usbi_warn(ctx, "short endpoint descriptor read %d/%u",
			  size, header->bLength);
		return parsed;
	}

	endpoint->bLength = buffer[0];
	endpoint->bDescriptorType = buffer[1];
	endpoint->bEndpointAddress = buffer[2];
	endpoint->bmAttributes = buffer[3];
	endpoint->wMaxPacketSize = ReadLittleEndian16(&buffer[4]);
	endpoint->bInterval = buffer[6];
	if (header->bLength >= LIBUSB_DT_ENDPOINT_AUDIO_SIZE) {
		endpoint->bRefresh = buffer[7];
		endpoint->bSynchAddress = buffer[8];
	}

	buffer += header->bLength;
	size -= header->bLength;
	parsed += header->bLength;

	/* Skip over the rest of the Class Specific or Vendor Specific */
	/*  descriptors */
	begin = buffer;
	while (size >= DESC_HEADER_LENGTH) {
		header = (const struct usbi_descriptor_header *)buffer;
		if (header->bLength < DESC_HEADER_LENGTH) {
			usbi_err(ctx, "invalid extra ep desc len (%u)",
				 header->bLength);
			return LIBUSB_ERROR_IO;
		} else if (header->bLength > size) {
			usbi_warn(ctx, "short extra ep desc read %d/%u",
				  size, header->bLength);
			return parsed;
		}

		/* If we find another "proper" descriptor then we're done  */
		if (header->bDescriptorType == LIBUSB_DT_ENDPOINT ||
		    header->bDescriptorType == LIBUSB_DT_INTERFACE ||
		    header->bDescriptorType == LIBUSB_DT_CONFIG ||
		    header->bDescriptorType == LIBUSB_DT_DEVICE)
			break;

		usbi_dbg(ctx, "skipping descriptor 0x%x", header->bDescriptorType);
		buffer += header->bLength;
		size -= header->bLength;
		parsed += header->bLength;
	}

	/* Copy any unknown descriptors into a storage area for drivers */
	/*  to later parse */
	ptrdiff_t len = buffer - begin;
	if (len <= 0)
		return parsed;

	extra = malloc((size_t)len);
	if (!extra)
		return LIBUSB_ERROR_NO_MEM;

	memcpy(extra, begin, (size_t)len);
	endpoint->extra = extra;
	endpoint->extra_length = (int)len;

	return parsed;
}

static void clear_interface(struct libusb_interface *usb_interface)
{
	int i;

	if (usb_interface->altsetting) {
		for (i = 0; i < usb_interface->num_altsetting; i++) {
			struct libusb_interface_descriptor *ifp =
				(struct libusb_interface_descriptor *)
				usb_interface->altsetting + i;

			free((void *)ifp->extra);
			ifp->extra = NULL;
			ifp->extra_length = 0;
			if (ifp->endpoint) {
				uint8_t j;

				for (j = 0; j < ifp->bNumEndpoints; j++)
					clear_endpoint((struct libusb_endpoint_descriptor *)
						       ifp->endpoint + j);
			}
			free((void *)ifp->endpoint);
			ifp->endpoint = NULL;
			ifp->bNumEndpoints = 0;
		}
	}
	free((void *)usb_interface->altsetting);
	usb_interface->altsetting = NULL;
	usb_interface->num_altsetting = 0;
}

static int parse_interface(libusb_context *ctx,
	struct libusb_interface *usb_interface, const uint8_t *buffer, int size)
{
	int r;
	int parsed = 0;
	int interface_number = -1;
	const struct usbi_descriptor_header *header;
	const struct usbi_interface_descriptor *if_desc;
	struct libusb_interface_descriptor *ifp;
	const uint8_t *begin;

	while (size >= LIBUSB_DT_INTERFACE_SIZE) {
		struct libusb_interface_descriptor *altsetting;

		altsetting = realloc((void *)usb_interface->altsetting,
			sizeof(*altsetting) * (size_t)(usb_interface->num_altsetting + 1));
		if (!altsetting) {
			r = LIBUSB_ERROR_NO_MEM;
			goto err;
		}
		usb_interface->altsetting = altsetting;

		ifp = altsetting + usb_interface->num_altsetting;
		ifp->bLength = buffer[0];
		ifp->bDescriptorType = buffer[1];
		ifp->bInterfaceNumber = buffer[2];
		ifp->bAlternateSetting = buffer[3];
		ifp->bNumEndpoints = buffer[4];
		ifp->bInterfaceClass = buffer[5];
		ifp->bInterfaceSubClass = buffer[6];
		ifp->bInterfaceProtocol = buffer[7];
		ifp->iInterface = buffer[8];
		if (ifp->bDescriptorType != LIBUSB_DT_INTERFACE) {
			usbi_err(ctx, "unexpected descriptor 0x%x (expected 0x%x)",
				 ifp->bDescriptorType, LIBUSB_DT_INTERFACE);
			return parsed;
		} else if (ifp->bLength < LIBUSB_DT_INTERFACE_SIZE) {
			usbi_err(ctx, "invalid interface bLength (%u)",
				 ifp->bLength);
			r = LIBUSB_ERROR_IO;
			goto err;
		} else if (ifp->bLength > size) {
			usbi_warn(ctx, "short intf descriptor read %d/%u",
				 size, ifp->bLength);
			return parsed;
		} else if (ifp->bNumEndpoints > USB_MAXENDPOINTS) {
			usbi_err(ctx, "too many endpoints (%u)", ifp->bNumEndpoints);
			r = LIBUSB_ERROR_IO;
			goto err;
		}

		usb_interface->num_altsetting++;
		ifp->extra = NULL;
		ifp->extra_length = 0;
		ifp->endpoint = NULL;

		if (interface_number == -1)
			interface_number = ifp->bInterfaceNumber;

		/* Skip over the interface */
		buffer += ifp->bLength;
		parsed += ifp->bLength;
		size -= ifp->bLength;

		begin = buffer;

		/* Skip over any interface, class or vendor descriptors */
		while (size >= DESC_HEADER_LENGTH) {
			header = (const struct usbi_descriptor_header *)buffer;
			if (header->bLength < DESC_HEADER_LENGTH) {
				usbi_err(ctx,
					 "invalid extra intf desc len (%u)",
					 header->bLength);
				r = LIBUSB_ERROR_IO;
				goto err;
			} else if (header->bLength > size) {
				usbi_warn(ctx,
					  "short extra intf desc read %d/%u",
					  size, header->bLength);
				/* Keep the invariant: bNumEndpoints > 0 implies
				 * endpoint != NULL. The endpoint array isn't
				 * allocated yet on this early return. */
				ifp->bNumEndpoints = 0;
				return parsed;
			}

			/* If we find another "proper" descriptor then we're done */
			if (header->bDescriptorType == LIBUSB_DT_INTERFACE ||
			    header->bDescriptorType == LIBUSB_DT_ENDPOINT ||
			    header->bDescriptorType == LIBUSB_DT_CONFIG ||
			    header->bDescriptorType == LIBUSB_DT_DEVICE)
				break;

			buffer += header->bLength;
			parsed += header->bLength;
			size -= header->bLength;
		}

		/* Copy any unknown descriptors into a storage area for */
		/*  drivers to later parse */
		ptrdiff_t len = buffer - begin;
		if (len > 0) {
			void *extra = malloc((size_t)len);

			if (!extra) {
				r = LIBUSB_ERROR_NO_MEM;
				goto err;
			}

			memcpy(extra, begin, (size_t)len);
			ifp->extra = extra;
			ifp->extra_length = (int)len;
		}

		if (ifp->bNumEndpoints > 0) {
			struct libusb_endpoint_descriptor *endpoint;
			uint8_t i;

			endpoint = calloc(ifp->bNumEndpoints, sizeof(*endpoint));
			if (!endpoint) {
				r = LIBUSB_ERROR_NO_MEM;
				goto err;
			}

			ifp->endpoint = endpoint;
			for (i = 0; i < ifp->bNumEndpoints; i++) {
				r = parse_endpoint(ctx, endpoint + i, buffer, size);
				if (r < 0)
					goto err;
				if (r == 0) {
					ifp->bNumEndpoints = i;
					break;
				}

				buffer += r;
				parsed += r;
				size -= r;
			}
		}

		/* We check to see if it's an alternate to this one */
		if_desc = (const struct usbi_interface_descriptor *)buffer;
		if (size < LIBUSB_DT_INTERFACE_SIZE ||
		    if_desc->bDescriptorType != LIBUSB_DT_INTERFACE ||
		    if_desc->bInterfaceNumber != interface_number)
			return parsed;
	}

	return parsed;
err:
	clear_interface(usb_interface);
	return r;
}

static void clear_configuration(struct libusb_config_descriptor *config)
{
	uint8_t i;

	if (config->interface) {
		for (i = 0; i < config->bNumInterfaces; i++)
			clear_interface((struct libusb_interface *)
					config->interface + i);
	}
	free((void *)config->interface);
	config->interface = NULL;
	config->bNumInterfaces = 0;
	free((void *)config->extra);
	config->extra = NULL;
	config->extra_length = 0;
}

static int parse_configuration(struct libusb_context *ctx,
	struct libusb_config_descriptor *config, const uint8_t *buffer, int size)
{
	uint8_t i;
	int r;
	const struct usbi_descriptor_header *header;
	struct libusb_interface *usb_interface;

	if (size < LIBUSB_DT_CONFIG_SIZE) {
		usbi_err(ctx, "short config descriptor read %d/%d",
			 size, LIBUSB_DT_CONFIG_SIZE);
		return LIBUSB_ERROR_IO;
	}

	config->bLength = buffer[0];
	config->bDescriptorType = buffer[1];
	config->wTotalLength = ReadLittleEndian16(&buffer[2]);
	config->bNumInterfaces = buffer[4];
	config->bConfigurationValue = buffer[5];
	config->iConfiguration = buffer[6];
	config->bmAttributes = buffer[7];
	config->MaxPower = buffer[8];
	if (config->bDescriptorType != LIBUSB_DT_CONFIG) {
		usbi_err(ctx, "unexpected descriptor 0x%x (expected 0x%x)",
			 config->bDescriptorType, LIBUSB_DT_CONFIG);
		return LIBUSB_ERROR_IO;
	} else if (config->bLength < LIBUSB_DT_CONFIG_SIZE) {
		usbi_err(ctx, "invalid config bLength (%u)", config->bLength);
		return LIBUSB_ERROR_IO;
	} else if (config->bLength > size) {
		usbi_err(ctx, "short config descriptor read %d/%u",
			 size, config->bLength);
		return LIBUSB_ERROR_IO;
	} else if (config->bNumInterfaces > USB_MAXINTERFACES) {
		usbi_err(ctx, "too many interfaces (%u)", config->bNumInterfaces);
		return LIBUSB_ERROR_IO;
	}

	usb_interface = calloc(config->bNumInterfaces, sizeof(*usb_interface));
	if (!usb_interface)
		return LIBUSB_ERROR_NO_MEM;

	config->interface = usb_interface;

	buffer += config->bLength;
	size -= config->bLength;

	for (i = 0; i < config->bNumInterfaces; i++) {
		const uint8_t *begin;

		/* Skip over the rest of the Class Specific or Vendor */
		/*  Specific descriptors */
		begin = buffer;
		while (size >= DESC_HEADER_LENGTH) {
			header = (const struct usbi_descriptor_header *)buffer;
			if (header->bLength < DESC_HEADER_LENGTH) {
				usbi_err(ctx,
					 "invalid extra config desc len (%u)",
					 header->bLength);
				r = LIBUSB_ERROR_IO;
				goto err;
			} else if (header->bLength > size) {
				usbi_warn(ctx,
					  "short extra config desc read %d/%u",
					  size, header->bLength);
				config->bNumInterfaces = i;
				return size;
			}

			/* If we find another "proper" descriptor then we're done */
			if (header->bDescriptorType == LIBUSB_DT_ENDPOINT ||
			    header->bDescriptorType == LIBUSB_DT_INTERFACE ||
			    header->bDescriptorType == LIBUSB_DT_CONFIG ||
			    header->bDescriptorType == LIBUSB_DT_DEVICE)
				break;

			usbi_dbg(ctx, "skipping descriptor 0x%x", header->bDescriptorType);
			buffer += header->bLength;
			size -= header->bLength;
		}

		/* Copy any unknown descriptors into a storage area for */
		/*  drivers to later parse */
		ptrdiff_t len = buffer - begin;
		if (len > 0) {
			uint8_t *extra = realloc((void *)config->extra,
						 (size_t)(config->extra_length) + (size_t)len);

			if (!extra) {
				r = LIBUSB_ERROR_NO_MEM;
				goto err;
			}

			memcpy(extra + config->extra_length, begin, (size_t)len);
			config->extra = extra;
			config->extra_length += (int)len;
		}

		r = parse_interface(ctx, usb_interface + i, buffer, size);
		if (r < 0)
			goto err;
		if (r == 0) {
			config->bNumInterfaces = i;
			break;
		}

		buffer += r;
		size -= r;
	}

	return size;

err:
	clear_configuration(config);
	return r;
}

static int raw_desc_to_config(struct libusb_context *ctx,
	const uint8_t *buf, int size, struct libusb_config_descriptor **config)
{
	struct libusb_config_descriptor *_config = calloc(1, sizeof(*_config));
	int r;

	if (!_config)
		return LIBUSB_ERROR_NO_MEM;

	r = parse_configuration(ctx, _config, buf, size);
	if (r < 0) {
		usbi_err(ctx, "parse_configuration failed with error %d", r);
		free(_config);
		return r;
	} else if (r > 0) {
		usbi_warn(ctx, "still %d bytes of descriptor data left", r);
	}

	*config = _config;
	return LIBUSB_SUCCESS;
}

static int get_active_config_descriptor(struct libusb_device *dev,
	uint8_t *buffer, size_t size)
{
	int r = usbi_backend.get_active_config_descriptor(dev, buffer, size);

	if (r < 0)
		return r;

	if (r < LIBUSB_DT_CONFIG_SIZE) {
		usbi_err(DEVICE_CTX(dev), "short config descriptor read %d/%d",
			 r, LIBUSB_DT_CONFIG_SIZE);
		return LIBUSB_ERROR_IO;
	} else if (r != (int)size) {
		usbi_warn(DEVICE_CTX(dev), "short config descriptor read %d/%d",
			 r, (int)size);
	}

	return r;
}

static int get_config_descriptor(struct libusb_device *dev, uint8_t config_idx,
	uint8_t *buffer, size_t size)
{
	int r = usbi_backend.get_config_descriptor(dev, config_idx, buffer, size);

	if (r < 0)
		return r;
	if (r < LIBUSB_DT_CONFIG_SIZE) {
		usbi_err(DEVICE_CTX(dev), "short config descriptor read %d/%d",
			 r, LIBUSB_DT_CONFIG_SIZE);
		return LIBUSB_ERROR_IO;
	} else if (r != (int)size) {
		usbi_warn(DEVICE_CTX(dev), "short config descriptor read %d/%d",
			 r, (int)size);
	}

	return r;
}

/** \ingroup libusb_desc
 * Get the USB device descriptor for a given device.
 *
 * This is a non-blocking function; the device descriptor is cached in memory.
 *
 * Note since libusb-1.0.16, \ref LIBUSBX_API_VERSION >= 0x01000102, this
 * function always succeeds.
 *
 * \param dev the device
 * \param desc output location for the descriptor data
 * \returns 0 on success or a LIBUSB_ERROR code on failure
 */
int API_EXPORTED libusb_get_device_descriptor(libusb_device *dev,
	struct libusb_device_descriptor *desc)
{
	usbi_dbg(DEVICE_CTX(dev), " ");
	static_assert(sizeof(dev->device_descriptor) == LIBUSB_DT_DEVICE_SIZE,
		      "struct libusb_device_descriptor is not expected size");
	*desc = dev->device_descriptor;
	return 0;
}

/** \ingroup libusb_desc
 * Get the USB configuration descriptor for the currently active configuration.
 * This is a non-blocking function which does not involve any requests being
 * sent to the device.
 *
 * \param dev a device
 * \param config output location for the USB configuration descriptor. Only
 * valid if 0 was returned. Must be freed with libusb_free_config_descriptor()
 * after use.
 * \returns 0 on success
 * \returns \ref LIBUSB_ERROR_NOT_FOUND if the device is in unconfigured state
 * \returns another LIBUSB_ERROR code on error
 * \see libusb_get_config_descriptor
 */
int API_EXPORTED libusb_get_active_config_descriptor(libusb_device *dev,
	struct libusb_config_descriptor **config)
{
	union usbi_config_desc_buf _config;
	uint16_t config_len;
	uint8_t *buf;
	int r;

	r = get_active_config_descriptor(dev, _config.buf, sizeof(_config.buf));
	if (r < 0)
		return r;

	config_len = libusb_le16_to_cpu(_config.desc.wTotalLength);
	buf = malloc(config_len);
	if (!buf)
		return LIBUSB_ERROR_NO_MEM;

	r = get_active_config_descriptor(dev, buf, config_len);
	if (r >= 0)
		r = raw_desc_to_config(DEVICE_CTX(dev), buf, r, config);

	free(buf);
	return r;
}

/** \ingroup libusb_desc
 * Get a USB configuration descriptor based on its index.
 * This is a non-blocking function which does not involve any requests being
 * sent to the device.
 *
 * \param dev a device
 * \param config_index the index of the configuration you wish to retrieve
 * \param config output location for the USB configuration descriptor. Only
 * valid if 0 was returned. Must be freed with libusb_free_config_descriptor()
 * after use.
 * \returns 0 on success
 * \returns \ref LIBUSB_ERROR_NOT_FOUND if the configuration does not exist
 * \returns another LIBUSB_ERROR code on error
 * \see libusb_get_active_config_descriptor()
 * \see libusb_get_config_descriptor_by_value()
 */
int API_EXPORTED libusb_get_config_descriptor(libusb_device *dev,
	uint8_t config_index, struct libusb_config_descriptor **config)
{
	union usbi_config_desc_buf _config;
	uint16_t config_len;
	uint8_t *buf;
	int r;

	usbi_dbg(DEVICE_CTX(dev), "index %u", config_index);
	if (config_index >= dev->device_descriptor.bNumConfigurations)
		return LIBUSB_ERROR_NOT_FOUND;

	r = get_config_descriptor(dev, config_index, _config.buf, sizeof(_config.buf));
	if (r < 0)
		return r;

	config_len = libusb_le16_to_cpu(_config.desc.wTotalLength);
	buf = malloc(config_len);
	if (!buf)
		return LIBUSB_ERROR_NO_MEM;

	r = get_config_descriptor(dev, config_index, buf, config_len);
	if (r >= 0)
		r = raw_desc_to_config(DEVICE_CTX(dev), buf, r, config);

	free(buf);
	return r;
}

/** \ingroup libusb_desc
 * Get a USB configuration descriptor with a specific bConfigurationValue.
 * This is a non-blocking function which does not involve any requests being
 * sent to the device.
 *
 * \param dev a device
 * \param bConfigurationValue the bConfigurationValue of the configuration you
 * wish to retrieve
 * \param config output location for the USB configuration descriptor. Only
 * valid if 0 was returned. Must be freed with libusb_free_config_descriptor()
 * after use.
 * \returns 0 on success
 * \returns \ref LIBUSB_ERROR_NOT_FOUND if the configuration does not exist
 * \returns another LIBUSB_ERROR code on error
 * \see libusb_get_active_config_descriptor()
 * \see libusb_get_config_descriptor()
 */
int API_EXPORTED libusb_get_config_descriptor_by_value(libusb_device *dev,
	uint8_t bConfigurationValue, struct libusb_config_descriptor **config)
{
	uint8_t idx;
	int r;

	if (usbi_backend.get_config_descriptor_by_value) {
		void *buf;

		r = usbi_backend.get_config_descriptor_by_value(dev,
			bConfigurationValue, &buf);
		if (r < 0)
			return r;

		return raw_desc_to_config(DEVICE_CTX(dev), buf, r, config);
	}

	usbi_dbg(DEVICE_CTX(dev), "value %u", bConfigurationValue);
	for (idx = 0; idx < dev->device_descriptor.bNumConfigurations; idx++) {
		union usbi_config_desc_buf _config;

		r = get_config_descriptor(dev, idx, _config.buf, sizeof(_config.buf));
		if (r < 0)
			return r;

		if (_config.desc.bConfigurationValue == bConfigurationValue)
			return libusb_get_config_descriptor(dev, idx, config);
	}

	return LIBUSB_ERROR_NOT_FOUND;
}

/** \ingroup libusb_desc
 * Free a configuration descriptor obtained from
 * libusb_get_active_config_descriptor() or libusb_get_config_descriptor().
 * It is safe to call this function with a NULL config parameter, in which
 * case the function simply returns.
 *
 * \param config the configuration descriptor to free
 */
void API_EXPORTED libusb_free_config_descriptor(
	struct libusb_config_descriptor *config)
{
	if (!config)
		return;

	clear_configuration(config);
	free(config);
}

/** \ingroup libusb_desc
 * Get an endpoints superspeed endpoint companion descriptor (if any)
 *
 * \param ctx the context to operate on, or NULL for the default context
 * \param endpoint endpoint descriptor from which to get the superspeed
 * endpoint companion descriptor
 * \param ep_comp output location for the superspeed endpoint companion
 * descriptor. Only valid if 0 was returned. Must be freed with
 * libusb_free_ss_endpoint_companion_descriptor() after use.
 * \returns 0 on success
 * \returns \ref LIBUSB_ERROR_NOT_FOUND if the configuration does not exist
 * \returns another LIBUSB_ERROR code on error
 */
int API_EXPORTED libusb_get_ss_endpoint_companion_descriptor(
	libusb_context *ctx,
	const struct libusb_endpoint_descriptor *endpoint,
	struct libusb_ss_endpoint_companion_descriptor **ep_comp)
{
	const struct usbi_descriptor_header *header;
	const uint8_t *buffer = endpoint->extra;
	int size = endpoint->extra_length;

	*ep_comp = NULL;

	while (size >= DESC_HEADER_LENGTH) {
		header = (const struct usbi_descriptor_header *)buffer;
		if (header->bDescriptorType != LIBUSB_DT_SS_ENDPOINT_COMPANION) {
			if (header->bLength < DESC_HEADER_LENGTH) {
				usbi_err(ctx, "invalid descriptor length %u",
					 header->bLength);
				return LIBUSB_ERROR_IO;
			}
			buffer += header->bLength;
			size -= header->bLength;
			continue;
		} else if (header->bLength < LIBUSB_DT_SS_ENDPOINT_COMPANION_SIZE) {
			usbi_err(ctx, "invalid ss-ep-comp-desc length %u",
				 header->bLength);
			return LIBUSB_ERROR_IO;
		} else if (header->bLength > size) {
			usbi_err(ctx, "short ss-ep-comp-desc read %d/%u",
				 size, header->bLength);
			return LIBUSB_ERROR_IO;
		}

		*ep_comp = malloc(sizeof(**ep_comp));
		if (!*ep_comp)
			return LIBUSB_ERROR_NO_MEM;
		(*ep_comp)->bLength = buffer[0];
		(*ep_comp)->bDescriptorType = buffer[1];
		(*ep_comp)->bMaxBurst = buffer[2];
		(*ep_comp)->bmAttributes = buffer[3];
		(*ep_comp)->wBytesPerInterval = ReadLittleEndian16(&buffer[4]);
		return LIBUSB_SUCCESS;
	}
	return LIBUSB_ERROR_NOT_FOUND;
}

/** \ingroup libusb_desc
 * Free a superspeed endpoint companion descriptor obtained from
 * libusb_get_ss_endpoint_companion_descriptor().
 * It is safe to call this function with a NULL ep_comp parameter, in which
 * case the function simply returns.
 *
 * \param ep_comp the superspeed endpoint companion descriptor to free
 */
void API_EXPORTED libusb_free_ss_endpoint_companion_descriptor(
	struct libusb_ss_endpoint_companion_descriptor *ep_comp)
{
	free(ep_comp);
}

static int parse_bos(struct libusb_context *ctx,
	struct libusb_bos_descriptor **bos,
	const uint8_t *buffer, int size)
{
	struct libusb_bos_descriptor *_bos;
	const struct usbi_bos_descriptor *bos_desc;
	const struct usbi_descriptor_header *header;
	uint8_t i;

	if (size < LIBUSB_DT_BOS_SIZE) {
		usbi_err(ctx, "short bos descriptor read %d/%d",
			 size, LIBUSB_DT_BOS_SIZE);
		return LIBUSB_ERROR_IO;
	}

	bos_desc = (const struct usbi_bos_descriptor *)buffer;
	if (bos_desc->bDescriptorType != LIBUSB_DT_BOS) {
		usbi_err(ctx, "unexpected descriptor 0x%x (expected 0x%x)",
			 bos_desc->bDescriptorType, LIBUSB_DT_BOS);
		return LIBUSB_ERROR_IO;
	} else if (bos_desc->bLength < LIBUSB_DT_BOS_SIZE) {
		usbi_err(ctx, "invalid bos bLength (%u)", bos_desc->bLength);
		return LIBUSB_ERROR_IO;
	} else if (bos_desc->bLength > size) {
		usbi_err(ctx, "short bos descriptor read %d/%u",
			 size, bos_desc->bLength);
		return LIBUSB_ERROR_IO;
	}

	_bos = calloc(1, sizeof(*_bos) + (bos_desc->bNumDeviceCaps * sizeof(void *)));
	if (!_bos)
		return LIBUSB_ERROR_NO_MEM;

	_bos->bLength = buffer[0];
	_bos->bDescriptorType = buffer[1];
	_bos->wTotalLength = ReadLittleEndian16(&buffer[2]);
	_bos->bNumDeviceCaps = buffer[4];
	buffer += _bos->bLength;
	size -= _bos->bLength;

	/* Get the device capability descriptors */
	for (i = 0; i < _bos->bNumDeviceCaps; i++) {
		if (size < LIBUSB_DT_DEVICE_CAPABILITY_SIZE) {
			usbi_warn(ctx, "short dev-cap descriptor read %d/%d",
				  size, LIBUSB_DT_DEVICE_CAPABILITY_SIZE);
			break;
		}
		header = (const struct usbi_descriptor_header *)buffer;
		if (header->bDescriptorType != LIBUSB_DT_DEVICE_CAPABILITY) {
			usbi_warn(ctx, "unexpected descriptor 0x%x (expected 0x%x)",
				  header->bDescriptorType, LIBUSB_DT_DEVICE_CAPABILITY);
			break;
		} else if (header->bLength < LIBUSB_DT_DEVICE_CAPABILITY_SIZE) {
			usbi_err(ctx, "invalid dev-cap bLength (%u)",
				 header->bLength);
			libusb_free_bos_descriptor(_bos);
			return LIBUSB_ERROR_IO;
		} else if (header->bLength > size) {
			usbi_warn(ctx, "short dev-cap descriptor read %d/%u",
				  size, header->bLength);
			break;
		}

		_bos->dev_capability[i] = malloc(header->bLength);
		if (!_bos->dev_capability[i]) {
			libusb_free_bos_descriptor(_bos);
			return LIBUSB_ERROR_NO_MEM;
		}
		memcpy(_bos->dev_capability[i], buffer, header->bLength);
		buffer += header->bLength;
		size -= header->bLength;
	}
	_bos->bNumDeviceCaps = i;
	*bos = _bos;

	return LIBUSB_SUCCESS;
}

/** \ingroup libusb_desc
 * Get a Binary Object Store (BOS) descriptor
 * This is a BLOCKING function, which will send requests to the device.
 *
 * \param dev_handle the handle of an open libusb device
 * \param bos output location for the BOS descriptor. Only valid if 0 was returned.
 * Must be freed with \ref libusb_free_bos_descriptor() after use.
 * \returns 0 on success
 * \returns \ref LIBUSB_ERROR_NOT_FOUND if the device doesn't have a BOS descriptor
 * \returns another LIBUSB_ERROR code on error
 */
int API_EXPORTED libusb_get_bos_descriptor(libusb_device_handle *dev_handle,
	struct libusb_bos_descriptor **bos)
{
	union usbi_bos_desc_buf _bos;
	uint16_t bos_len;
	uint8_t *bos_data;
	int r;
	struct libusb_context *ctx = HANDLE_CTX(dev_handle);

	/* Read the BOS. This generates 2 requests on the bus,
	 * one for the header, and one for the full BOS */
	r = libusb_get_descriptor(dev_handle, LIBUSB_DT_BOS, 0, _bos.buf, sizeof(_bos.buf));
	if (r < 0) {
		if (r != LIBUSB_ERROR_PIPE)
			usbi_err(ctx, "failed to read BOS (%d)", r);
		return r;
	}
	if (r < LIBUSB_DT_BOS_SIZE) {
		usbi_err(ctx, "short BOS read %d/%d",
			 r, LIBUSB_DT_BOS_SIZE);
		return LIBUSB_ERROR_IO;
	}

	bos_len = libusb_le16_to_cpu(_bos.desc.wTotalLength);
	usbi_dbg(ctx, "found BOS descriptor: size %u bytes, %u capabilities",
		 bos_len, _bos.desc.bNumDeviceCaps);
	bos_data = calloc(1, bos_len);
	if (!bos_data)
		return LIBUSB_ERROR_NO_MEM;

	r = libusb_get_descriptor(dev_handle, LIBUSB_DT_BOS, 0, bos_data, bos_len);
	if (r >= 0) {
		if (r != (int)bos_len)
			usbi_warn(ctx, "short BOS read %d/%u", r, bos_len);
		r = parse_bos(HANDLE_CTX(dev_handle), bos, bos_data, r);
	} else {
		usbi_err(ctx, "failed to read BOS (%d)", r);
	}

	free(bos_data);
	return r;
}

/** \ingroup libusb_desc
 * Free a BOS descriptor obtained from libusb_get_bos_descriptor().
 * It is safe to call this function with a NULL bos parameter, in which
 * case the function simply returns.
 *
 * \param bos the BOS descriptor to free
 */
void API_EXPORTED libusb_free_bos_descriptor(struct libusb_bos_descriptor *bos)
{
	uint8_t i;

	if (!bos)
		return;

	for (i = 0; i < bos->bNumDeviceCaps; i++)
		free(bos->dev_capability[i]);
	free(bos);
}

/** \ingroup libusb_desc
 * Get an USB 2.0 Extension descriptor
 *
 * \param ctx the context to operate on, or NULL for the default context
 * \param dev_cap Device Capability descriptor with a bDevCapabilityType of
 * \ref libusb_bos_type::LIBUSB_BT_USB_2_0_EXTENSION
 * LIBUSB_BT_USB_2_0_EXTENSION
 * \param usb_2_0_extension output location for the USB 2.0 Extension
 * descriptor. Only valid if 0 was returned. Must be freed with
 * libusb_free_usb_2_0_extension_descriptor() after use.
 * \returns 0 on success
 * \returns a LIBUSB_ERROR code on error
 */
int API_EXPORTED libusb_get_usb_2_0_extension_descriptor(
	libusb_context *ctx,
	struct libusb_bos_dev_capability_descriptor *dev_cap,
	struct libusb_usb_2_0_extension_descriptor **usb_2_0_extension)
{
	struct libusb_usb_2_0_extension_descriptor *_usb_2_0_extension;

	if (dev_cap->bDevCapabilityType != LIBUSB_BT_USB_2_0_EXTENSION) {
		usbi_err(ctx, "unexpected bDevCapabilityType 0x%x (expected 0x%x)",
			 dev_cap->bDevCapabilityType,
			 LIBUSB_BT_USB_2_0_EXTENSION);
		return LIBUSB_ERROR_INVALID_PARAM;
	} else if (dev_cap->bLength < LIBUSB_BT_USB_2_0_EXTENSION_SIZE) {
		usbi_err(ctx, "short dev-cap descriptor read %u/%d",
			 dev_cap->bLength, LIBUSB_BT_USB_2_0_EXTENSION_SIZE);
		return LIBUSB_ERROR_IO;
	}

	_usb_2_0_extension = malloc(sizeof(*_usb_2_0_extension));
	if (!_usb_2_0_extension)
		return LIBUSB_ERROR_NO_MEM;

	_usb_2_0_extension->bLength = dev_cap->bLength;
	_usb_2_0_extension->bDescriptorType = dev_cap->bDescriptorType;
	_usb_2_0_extension->bDevCapabilityType = dev_cap->bDevCapabilityType;
	_usb_2_0_extension->bmAttributes = ReadLittleEndian32(dev_cap->dev_capability_data);

	*usb_2_0_extension = _usb_2_0_extension;
	return LIBUSB_SUCCESS;
}

/** \ingroup libusb_desc
 * Free a USB 2.0 Extension descriptor obtained from
 * libusb_get_usb_2_0_extension_descriptor().
 * It is safe to call this function with a NULL usb_2_0_extension parameter,
 * in which case the function simply returns.
 *
 * \param usb_2_0_extension the USB 2.0 Extension descriptor to free
 */
void API_EXPORTED libusb_free_usb_2_0_extension_descriptor(
	struct libusb_usb_2_0_extension_descriptor *usb_2_0_extension)
{
	free(usb_2_0_extension);
}

/** \ingroup libusb_desc
 * Get a SuperSpeed USB Device Capability descriptor
 *
 * \param ctx the context to operate on, or NULL for the default context
 * \param dev_cap Device Capability descriptor with a bDevCapabilityType of
 * \ref libusb_bos_type::LIBUSB_BT_SS_USB_DEVICE_CAPABILITY
 * LIBUSB_BT_SS_USB_DEVICE_CAPABILITY
 * \param ss_usb_device_cap output location for the SuperSpeed USB Device
 * Capability descriptor. Only valid if 0 was returned. Must be freed with
 * libusb_free_ss_usb_device_capability_descriptor() after use.
 * \returns 0 on success
 * \returns a LIBUSB_ERROR code on error
 */
int API_EXPORTED libusb_get_ss_usb_device_capability_descriptor(
	libusb_context *ctx,
	struct libusb_bos_dev_capability_descriptor *dev_cap,
	struct libusb_ss_usb_device_capability_descriptor **ss_usb_device_cap)
{
	struct libusb_ss_usb_device_capability_descriptor *_ss_usb_device_cap;

	if (dev_cap->bDevCapabilityType != LIBUSB_BT_SS_USB_DEVICE_CAPABILITY) {
		usbi_err(ctx, "unexpected bDevCapabilityType 0x%x (expected 0x%x)",
			 dev_cap->bDevCapabilityType,
			 LIBUSB_BT_SS_USB_DEVICE_CAPABILITY);
		return LIBUSB_ERROR_INVALID_PARAM;
	} else if (dev_cap->bLength < LIBUSB_BT_SS_USB_DEVICE_CAPABILITY_SIZE) {
		usbi_err(ctx, "short dev-cap descriptor read %u/%d",
			 dev_cap->bLength, LIBUSB_BT_SS_USB_DEVICE_CAPABILITY_SIZE);
		return LIBUSB_ERROR_IO;
	}

	_ss_usb_device_cap = malloc(sizeof(*_ss_usb_device_cap));
	if (!_ss_usb_device_cap)
		return LIBUSB_ERROR_NO_MEM;

	_ss_usb_device_cap->bLength = dev_cap->bLength;
	_ss_usb_device_cap->bDescriptorType = dev_cap->bDescriptorType;
	_ss_usb_device_cap->bDevCapabilityType = dev_cap->bDevCapabilityType;
	_ss_usb_device_cap->bmAttributes = dev_cap->dev_capability_data[0];
	_ss_usb_device_cap->wSpeedSupported = ReadLittleEndian16(&dev_cap->dev_capability_data[1]);
	_ss_usb_device_cap->bFunctionalitySupport = dev_cap->dev_capability_data[3];
	_ss_usb_device_cap->bU1DevExitLat = dev_cap->dev_capability_data[4];
	_ss_usb_device_cap->bU2DevExitLat = ReadLittleEndian16(&dev_cap->dev_capability_data[5]);

	*ss_usb_device_cap = _ss_usb_device_cap;
	return LIBUSB_SUCCESS;
}

/// @cond DEV
/** \internal \ingroup libusb_desc
 * We use this private struct only to parse a SuperSpeedPlus device capability
 * descriptor according to section 9.6.2.5 of the USB 3.1 specification.
 * We don't expose it.
 */
struct internal_ssplus_capability_descriptor {
	/** The length of the descriptor. Must be equal to LIBUSB_BT_SSPLUS_USB_DEVICE_CAPABILITY_SIZE */
	uint8_t  bLength;

	/** The type of the descriptor */
	uint8_t  bDescriptorType;

	/** Must be equal to LIBUSB_BT_SUPERSPEED_PLUS_CAPABILITY */
	uint8_t  bDevCapabilityType;

	/** Unused */
	uint8_t  bReserved;

	/** Contains the number of SublinkSpeedIDs */
	uint32_t bmAttributes;

	/** Contains the ssid, minRxLaneCount, and minTxLaneCount */
	uint16_t wFunctionalitySupport;

	/** Unused */
	uint16_t wReserved;
};
/// @endcond

/** \ingroup libusb_desc
 * Get a SuperSpeedPlus USB Device Capability descriptor
 *
 * Since version 1.0.28, \ref LIBUSB_API_VERSION >= 0x0100010B
 *
 * \param ctx the context to operate on, or NULL for the default context
 * \param dev_cap Device Capability descriptor with a bDevCapabilityType of
 * \ref libusb_bos_type::LIBUSB_BT_SUPERSPEED_PLUS_CAPABILITY
 * LIBUSB_BT_SUPERSPEED_PLUS_CAPABILITY
 * \param ssplus_usb_device_cap output location for the SuperSpeedPlus USB Device
 * Capability descriptor. Only valid if 0 was returned. Must be freed with
 * libusb_free_ssplus_usb_device_capability_descriptor() after use.
 * \returns 0 on success
 * \returns a LIBUSB_ERROR code on error
 */
int API_EXPORTED libusb_get_ssplus_usb_device_capability_descriptor(
	libusb_context *ctx,
	struct libusb_bos_dev_capability_descriptor *dev_cap,
	struct libusb_ssplus_usb_device_capability_descriptor **ssplus_usb_device_cap)
{
	struct libusb_ssplus_usb_device_capability_descriptor *_ssplus_cap;

	/* Use a private struct to reuse our descriptor parsing system. */
	struct internal_ssplus_capability_descriptor parsedDescriptor;

	/* Some size/type checks to make sure everything is in order */
	if (dev_cap->bDevCapabilityType != LIBUSB_BT_SUPERSPEED_PLUS_CAPABILITY) {
		usbi_err(ctx, "unexpected bDevCapabilityType 0x%x (expected 0x%x)",
			 dev_cap->bDevCapabilityType,
				 LIBUSB_BT_SUPERSPEED_PLUS_CAPABILITY);
		return LIBUSB_ERROR_INVALID_PARAM;
	} else if (dev_cap->bLength < LIBUSB_BT_SSPLUS_USB_DEVICE_CAPABILITY_SIZE) {
		usbi_err(ctx, "short dev-cap descriptor read %u/%d",
			 dev_cap->bLength, LIBUSB_BT_SSPLUS_USB_DEVICE_CAPABILITY_SIZE);
		return LIBUSB_ERROR_IO;
	}

	const uint8_t* dev_capability_data = dev_cap->dev_capability_data;
	parsedDescriptor.bLength = dev_cap->bLength;
	parsedDescriptor.bDescriptorType = dev_cap->bDescriptorType;
	parsedDescriptor.bDevCapabilityType = dev_cap->bDevCapabilityType;
	parsedDescriptor.bReserved = dev_capability_data[0];
	parsedDescriptor.bmAttributes = ReadLittleEndian32(&dev_capability_data[1]);
	parsedDescriptor.wFunctionalitySupport = ReadLittleEndian16(&dev_capability_data[5]);
	parsedDescriptor.wReserved = ReadLittleEndian16(&dev_capability_data[7]);

	uint8_t numSublikSpeedAttributes = (parsedDescriptor.bmAttributes & 0xF) + 1;
	_ssplus_cap = malloc(sizeof(struct libusb_ssplus_usb_device_capability_descriptor) + (numSublikSpeedAttributes * sizeof(struct libusb_ssplus_sublink_attribute)));
	if (!_ssplus_cap)
		return LIBUSB_ERROR_NO_MEM;

	/* Parse bmAttributes */
	_ssplus_cap->numSublinkSpeedAttributes = numSublikSpeedAttributes;
	_ssplus_cap->numSublinkSpeedIDs = ((parsedDescriptor.bmAttributes & 0xF0) >> 4) + 1;

	/* Parse wFunctionalitySupport */
	_ssplus_cap->ssid = parsedDescriptor.wFunctionalitySupport & 0xF;
	_ssplus_cap->minRxLaneCount = (parsedDescriptor.wFunctionalitySupport & 0x0F00) >> 8;
	_ssplus_cap->minTxLaneCount = (parsedDescriptor.wFunctionalitySupport & 0xF000) >> 12;

	/* Check that we have enough to read all the sublink attributes */
	if (dev_cap->bLength < LIBUSB_BT_SSPLUS_USB_DEVICE_CAPABILITY_SIZE + (_ssplus_cap->numSublinkSpeedAttributes * sizeof(uint32_t))) {
		free(_ssplus_cap);
		usbi_err(ctx, "short ssplus capability descriptor, unable to read sublinks: Not enough data");
		return LIBUSB_ERROR_IO;
	}

	/* Read the attributes */
	uint8_t* base = ((uint8_t*)dev_cap) + LIBUSB_BT_SSPLUS_USB_DEVICE_CAPABILITY_SIZE;
	for(uint8_t i = 0 ; i < _ssplus_cap->numSublinkSpeedAttributes ; i++) {
		uint32_t attr = ReadLittleEndian32(base + (i * sizeof(uint32_t)));
		_ssplus_cap->sublinkSpeedAttributes[i].ssid = attr & 0x0f;
		_ssplus_cap->sublinkSpeedAttributes[i].mantissa = attr >> 16;
		_ssplus_cap->sublinkSpeedAttributes[i].exponent = (attr >> 4) & 0x3 ;
		_ssplus_cap->sublinkSpeedAttributes[i].type = attr & 0x40 ? 	LIBUSB_SSPLUS_ATTR_TYPE_ASYM : LIBUSB_SSPLUS_ATTR_TYPE_SYM;
		_ssplus_cap->sublinkSpeedAttributes[i].direction = attr & 0x80 ? 	LIBUSB_SSPLUS_ATTR_DIR_TX : 	LIBUSB_SSPLUS_ATTR_DIR_RX;
		_ssplus_cap->sublinkSpeedAttributes[i].protocol = attr & 0x4000 ? LIBUSB_SSPLUS_ATTR_PROT_SSPLUS: LIBUSB_SSPLUS_ATTR_PROT_SS;
	}

	*ssplus_usb_device_cap = _ssplus_cap;
	return LIBUSB_SUCCESS;
}

/** \ingroup libusb_desc
 * Free a SuperSpeedPlus USB Device Capability descriptor obtained from
 * libusb_get_ssplus_usb_device_capability_descriptor().
 * It is safe to call this function with a NULL ssplus_usb_device_cap
 * parameter, in which case the function simply returns.
 *
 * Since version 1.0.28, \ref LIBUSB_API_VERSION >= 0x0100010B
 *
 * \param ssplus_usb_device_cap the SuperSpeedPlus USB Device Capability descriptor
 * to free
 */
void API_EXPORTED libusb_free_ssplus_usb_device_capability_descriptor(
	struct libusb_ssplus_usb_device_capability_descriptor *ssplus_usb_device_cap)
{
	free(ssplus_usb_device_cap);
}



/** \ingroup libusb_desc
 * Free a SuperSpeed USB Device Capability descriptor obtained from
 * libusb_get_ss_usb_device_capability_descriptor().
 * It is safe to call this function with a NULL ss_usb_device_cap
 * parameter, in which case the function simply returns.
 *
 * \param ss_usb_device_cap the SuperSpeed USB Device Capability descriptor
 * to free
 */
void API_EXPORTED libusb_free_ss_usb_device_capability_descriptor(
	struct libusb_ss_usb_device_capability_descriptor *ss_usb_device_cap)
{
	free(ss_usb_device_cap);
}

/** \ingroup libusb_desc
 * Get a Container ID descriptor
 *
 * \param ctx the context to operate on, or NULL for the default context
 * \param dev_cap Device Capability descriptor with a bDevCapabilityType of
 * \ref libusb_bos_type::LIBUSB_BT_CONTAINER_ID
 * LIBUSB_BT_CONTAINER_ID
 * \param container_id output location for the Container ID descriptor.
 * Only valid if 0 was returned. Must be freed with
 * libusb_free_container_id_descriptor() after use.
 * \returns 0 on success
 * \returns a LIBUSB_ERROR code on error
 */
int API_EXPORTED libusb_get_container_id_descriptor(libusb_context *ctx,
	struct libusb_bos_dev_capability_descriptor *dev_cap,
	struct libusb_container_id_descriptor **container_id)
{
	struct libusb_container_id_descriptor *_container_id;

	if (dev_cap->bDevCapabilityType != LIBUSB_BT_CONTAINER_ID) {
		usbi_err(ctx, "unexpected bDevCapabilityType 0x%x (expected 0x%x)",
			 dev_cap->bDevCapabilityType,
			 LIBUSB_BT_CONTAINER_ID);
		return LIBUSB_ERROR_INVALID_PARAM;
	} else if (dev_cap->bLength < LIBUSB_BT_CONTAINER_ID_SIZE) {
		usbi_err(ctx, "short dev-cap descriptor read %u/%d",
			 dev_cap->bLength, LIBUSB_BT_CONTAINER_ID_SIZE);
		return LIBUSB_ERROR_IO;
	}

	_container_id = malloc(sizeof(*_container_id));
	if (!_container_id)
		return LIBUSB_ERROR_NO_MEM;

	_container_id->bLength = dev_cap->bLength;
	_container_id->bDescriptorType = dev_cap->bDescriptorType;
	_container_id->bDevCapabilityType = dev_cap->bDevCapabilityType;
	_container_id->bReserved = dev_cap->dev_capability_data[0];
	memcpy(_container_id->ContainerID, &dev_cap->dev_capability_data[1], 16);

	*container_id = _container_id;
	return LIBUSB_SUCCESS;
}

/** \ingroup libusb_desc
 * Free a Container ID descriptor obtained from
 * libusb_get_container_id_descriptor().
 * It is safe to call this function with a NULL container_id parameter,
 * in which case the function simply returns.
 *
 * \param container_id the Container ID descriptor to free
 */
void API_EXPORTED libusb_free_container_id_descriptor(
	struct libusb_container_id_descriptor *container_id)
{
	free(container_id);
}

/** \ingroup libusb_desc
 * Get a platform descriptor
 *
 * Since version 1.0.27, \ref LIBUSB_API_VERSION >= 0x0100010A
 *
 * \param ctx the context to operate on, or NULL for the default context
 * \param dev_cap Device Capability descriptor with a bDevCapabilityType of
 * \ref libusb_bos_type::LIBUSB_BT_PLATFORM_DESCRIPTOR
 * LIBUSB_BT_PLATFORM_DESCRIPTOR
 * \param platform_descriptor output location for the Platform descriptor.
 * Only valid if 0 was returned. Must be freed with
 * libusb_free_platform_descriptor() after use.
 * \returns 0 on success
 * \returns a LIBUSB_ERROR code on error
 */
int API_EXPORTED libusb_get_platform_descriptor(libusb_context *ctx,
	struct libusb_bos_dev_capability_descriptor *dev_cap,
	struct libusb_platform_descriptor **platform_descriptor)
{
	struct libusb_platform_descriptor *_platform_descriptor;

	if (dev_cap->bDevCapabilityType != LIBUSB_BT_PLATFORM_DESCRIPTOR) {
		usbi_err(ctx, "unexpected bDevCapabilityType 0x%x (expected 0x%x)",
			 dev_cap->bDevCapabilityType,
			 LIBUSB_BT_PLATFORM_DESCRIPTOR);
		return LIBUSB_ERROR_INVALID_PARAM;
	} else if (dev_cap->bLength < LIBUSB_BT_PLATFORM_DESCRIPTOR_MIN_SIZE) {
		usbi_err(ctx, "short dev-cap descriptor read %u/%d",
			 dev_cap->bLength, LIBUSB_BT_PLATFORM_DESCRIPTOR_MIN_SIZE);
		return LIBUSB_ERROR_IO;
	}

	_platform_descriptor = malloc(dev_cap->bLength);
	if (!_platform_descriptor)
		return LIBUSB_ERROR_NO_MEM;

	_platform_descriptor->bLength = dev_cap->bLength;
	_platform_descriptor->bDescriptorType = dev_cap->bDescriptorType;
	_platform_descriptor->bDevCapabilityType = dev_cap->bDevCapabilityType;
	_platform_descriptor->bReserved = dev_cap->dev_capability_data[0];
	memcpy(_platform_descriptor->PlatformCapabilityUUID, &(dev_cap->dev_capability_data[1]), 16);

	/* Capability data is located after reserved byte and 16 byte UUID */
	uint8_t* capability_data = dev_cap->dev_capability_data + 1 + 16;

	/* Capability data length is total descriptor length minus initial fields */
	size_t capability_data_length = dev_cap->bLength - (3 + 1 + 16);

	memcpy(_platform_descriptor->CapabilityData, capability_data, capability_data_length);

	*platform_descriptor = _platform_descriptor;
	return LIBUSB_SUCCESS;
}

/** \ingroup libusb_desc
 * Free a platform descriptor obtained from
 * libusb_get_platform_descriptor().
 * It is safe to call this function with a NULL platform_descriptor parameter,
 * in which case the function simply returns.
 *
 * \param platform_descriptor the Platform descriptor to free
 */
void API_EXPORTED libusb_free_platform_descriptor(
	struct libusb_platform_descriptor *platform_descriptor)
{
	free(platform_descriptor);
}

/** \ingroup libusb_desc
 * Retrieve a string descriptor in C style ASCII.
 *
 * Wrapper around libusb_get_string_descriptor(). Uses the first language
 * supported by the device.
 *
 * \param dev_handle a device handle
 * \param desc_index the index of the descriptor to retrieve
 * \param data output buffer for ASCII string descriptor
 * \param length size of data buffer
 * \returns number of bytes returned in data, or LIBUSB_ERROR code on failure
 */
int API_EXPORTED libusb_get_string_descriptor_ascii(libusb_device_handle *dev_handle,
	uint8_t desc_index, unsigned char *data, int length)
{
	union usbi_string_desc_buf str;
	int r;
	uint16_t langid, wdata;

	/* Asking for the zero'th index is special - it returns a string
	 * descriptor that contains all the language IDs supported by the
	 * device. Typically there aren't many - often only one. Language
	 * IDs are 16 bit numbers, and they start at the third byte in the
	 * descriptor. There's also no point in trying to read descriptor 0
	 * with this function. See USB 2.0 specification section 9.6.7 for
	 * more information.
	 */

	if (desc_index == 0)
		return LIBUSB_ERROR_INVALID_PARAM;

	r = libusb_get_string_descriptor(dev_handle, 0, 0, str.buf, 4);
	if (r < 0)
		return r;
	else if (r != 4 || str.desc.bLength < 4 || str.desc.bDescriptorType != LIBUSB_DT_STRING) {
		usbi_warn(HANDLE_CTX(dev_handle), "invalid language ID string descriptor");
		return LIBUSB_ERROR_IO;
	} else if (str.desc.bLength & 1)
		usbi_warn(HANDLE_CTX(dev_handle), "suspicious bLength %u for language ID string descriptor", str.desc.bLength);

	langid = libusb_le16_to_cpu(str.desc.wData[0]);
	r = libusb_get_string_descriptor(dev_handle, desc_index, langid, str.buf, sizeof(str.buf));
	if (r < 0)
		return r;
	else if (r < DESC_HEADER_LENGTH || str.desc.bLength > r || str.desc.bDescriptorType != LIBUSB_DT_STRING)
		return LIBUSB_ERROR_IO;
	else if ((str.desc.bLength & 1) || str.desc.bLength != r)
		usbi_warn(HANDLE_CTX(dev_handle), "suspicious bLength %u for string descriptor (read %d)", str.desc.bLength, r);

	/* Stop one byte before the end to leave room for null termination. */
	int dest_max = length - 1;

	/* The descriptor has this number of wide characters */
	int src_max = (str.desc.bLength - 1 - 1) / 2;

	/* Neither read nor write more than the smallest buffer */
	int idx_max = MIN(dest_max, src_max);

	int idx;
	for (idx = 0; idx < idx_max; ++idx) {
		wdata = libusb_le16_to_cpu(str.desc.wData[idx]);
		if (wdata < 0x80)
			data[idx] = (unsigned char)wdata;
		else
			data[idx] = '?'; /* non-ASCII */
	}

	data[idx] = 0; /* null-terminate string */
	return idx;
}

static int parse_iad_array(struct libusb_context *ctx,
	struct libusb_interface_association_descriptor_array *iad_array,
	const uint8_t *buffer, int size)
{
	uint8_t i;
	struct usbi_descriptor_header header;
	int consumed = 0;
	const uint8_t *buf = buffer;
	struct libusb_interface_association_descriptor *iad;

	if (size < LIBUSB_DT_CONFIG_SIZE) {
		usbi_err(ctx, "short config descriptor read %d/%d",
			 size, LIBUSB_DT_CONFIG_SIZE);
		return LIBUSB_ERROR_IO;
	}

	/* First pass: Iterate through desc list, count number of IADs */
	iad_array->length = 0;
	while (size - consumed >= DESC_HEADER_LENGTH) {
		header.bLength = buf[0];
		header.bDescriptorType = buf[1];
		if (header.bLength < DESC_HEADER_LENGTH) {
			usbi_err(ctx, "invalid descriptor bLength %d",
				 header.bLength);
			return LIBUSB_ERROR_IO;
		}
		else if (header.bLength > size - consumed) {
			usbi_warn(ctx, "short config descriptor read %d/%u",
					  size - consumed, header.bLength);
			return LIBUSB_ERROR_IO;
		}
		if (header.bDescriptorType == LIBUSB_DT_INTERFACE_ASSOCIATION)
			iad_array->length++;
		buf += header.bLength;
		consumed += header.bLength;
	}

	iad_array->iad = NULL;
	if (iad_array->length > 0) {
		iad = calloc((size_t)iad_array->length, sizeof(*iad));
		if (!iad)
			return LIBUSB_ERROR_NO_MEM;

		iad_array->iad = iad;

		/* Second pass: Iterate through desc list, fill IAD structures */
		int remaining = size;
		i = 0;
		do {
			header.bLength = buffer[0];
			header.bDescriptorType = buffer[1];
			if (header.bDescriptorType == LIBUSB_DT_INTERFACE_ASSOCIATION && (remaining >= LIBUSB_DT_INTERFACE_ASSOCIATION_SIZE)) {
				iad[i].bLength = buffer[0];
				iad[i].bDescriptorType = buffer[1];
				iad[i].bFirstInterface = buffer[2];
				iad[i].bInterfaceCount = buffer[3];
				iad[i].bFunctionClass = buffer[4];
				iad[i].bFunctionSubClass = buffer[5];
				iad[i].bFunctionProtocol = buffer[6];
				iad[i].iFunction = buffer[7];
				i++;
			}

			remaining -= header.bLength;
			if (remaining < DESC_HEADER_LENGTH) {
				break;
			}
			buffer += header.bLength;
		} while (1);
	}

	return LIBUSB_SUCCESS;
}

static int raw_desc_to_iad_array(struct libusb_context *ctx, const uint8_t *buf,
		int size, struct libusb_interface_association_descriptor_array **iad_array)
{
	struct libusb_interface_association_descriptor_array *_iad_array
		= calloc(1, sizeof(*_iad_array));
	int r;

	if (!_iad_array)
		return LIBUSB_ERROR_NO_MEM;

	r = parse_iad_array(ctx, _iad_array, buf, size);
	if (r < 0) {
		usbi_err(ctx, "parse_iad_array failed with error %d", r);
		free(_iad_array);
		return r;
	}

	*iad_array = _iad_array;
	return LIBUSB_SUCCESS;
}

/** \ingroup libusb_desc
 * Get an array of interface association descriptors (IAD) for a given
 * configuration.
 * This is a non-blocking function which does not involve any requests being
 * sent to the device.
 *
 * \param dev a device
 * \param config_index the index of the configuration you wish to retrieve the
 * IADs for.
 * \param iad_array output location for the array of IADs. Only valid if 0 was
 * returned. Must be freed with libusb_free_interface_association_descriptors()
 * after use. It's possible that a given configuration contains no IADs. In this
 * case the iad_array is still output, but will have 'length' field set to 0, and
 * iad field set to NULL.
 * \returns 0 on success
 * \returns LIBUSB_ERROR_NOT_FOUND if the configuration does not exist
 * \returns another LIBUSB_ERROR code on error
 * \see libusb_get_active_interface_association_descriptors()
 */
int API_EXPORTED libusb_get_interface_association_descriptors(libusb_device *dev,
	uint8_t config_index, struct libusb_interface_association_descriptor_array **iad_array)
{
	union usbi_config_desc_buf _config;
	uint16_t config_len;
	uint8_t *buf;
	int r;

	if (!iad_array)
		return LIBUSB_ERROR_INVALID_PARAM;

	usbi_dbg(DEVICE_CTX(dev), "IADs for config index %u", config_index);
	if (config_index >= dev->device_descriptor.bNumConfigurations)
		return LIBUSB_ERROR_NOT_FOUND;

	r = get_config_descriptor(dev, config_index, _config.buf, sizeof(_config.buf));
	if (r < 0)
		return r;

	config_len = libusb_le16_to_cpu(_config.desc.wTotalLength);
	buf = malloc(config_len);
	if (!buf)
		return LIBUSB_ERROR_NO_MEM;

	r = get_config_descriptor(dev, config_index, buf, config_len);
	if (r >= 0)
		r = raw_desc_to_iad_array(DEVICE_CTX(dev), buf, r, iad_array);

	free(buf);
	return r;
}

/** \ingroup libusb_desc
 * Get an array of interface association descriptors (IAD) for the currently
 * active configuration.
 * This is a non-blocking function which does not involve any requests being
 * sent to the device.
 *
 * \param dev a device
 * \param iad_array output location for the array of IADs. Only valid if 0 was
 * returned. Must be freed with libusb_free_interface_association_descriptors()
 * after use. It's possible that a given configuration contains no IADs. In this
 * case the iad_array is still output, but will have 'length' field set to 0, and
 * iad field set to NULL.
 * \returns 0 on success
 * \returns LIBUSB_ERROR_NOT_FOUND if the device is in unconfigured state
 * \returns another LIBUSB_ERROR code on error
 * \see libusb_get_interface_association_descriptors
 */
int API_EXPORTED libusb_get_active_interface_association_descriptors(libusb_device *dev,
	struct libusb_interface_association_descriptor_array **iad_array)
{
	union usbi_config_desc_buf _config;
	uint16_t config_len;
	uint8_t *buf;
	int r;

	if (!iad_array)
		return LIBUSB_ERROR_INVALID_PARAM;

	r = get_active_config_descriptor(dev, _config.buf, sizeof(_config.buf));
	if (r < 0)
		return r;

	config_len = libusb_le16_to_cpu(_config.desc.wTotalLength);
	buf = malloc(config_len);
	if (!buf)
		return LIBUSB_ERROR_NO_MEM;

	r = get_active_config_descriptor(dev, buf, config_len);
	if (r >= 0)
		r = raw_desc_to_iad_array(DEVICE_CTX(dev), buf, r, iad_array);
	free(buf);
	return r;
}

/** \ingroup libusb_desc
 * Free an array of interface association descriptors (IADs) obtained from
 * libusb_get_interface_association_descriptors() or
 * libusb_get_active_interface_association_descriptors().
 * It is safe to call this function with a NULL iad_array parameter, in which
 * case the function simply returns.
 *
 * \param iad_array the IAD array to free
 */
void API_EXPORTED libusb_free_interface_association_descriptors(
	struct libusb_interface_association_descriptor_array *iad_array)
{
	if (!iad_array)
		return;

	if (iad_array->iad)
		free((void*)iad_array->iad);
	free(iad_array);
}

/*
 * \brief Copy a UTF-8 string with proper truncation if needed.
 *
 * \param tgt The target utf-8 string.  If NULL, then tgt is ignored,
 *            tgt_size is forced to 0, and this function returns the
 *            required tgt_size for a subsequent call to this function.
 * \param src The source utf-8 string.  If NULL, then
 *            the source string is empty, the return length is 1,
 *            and, if tgt is not NULL, this function
 *            sets tgt[0] to the null terminator.
 * \param tgt_size The size of target in bytes.
 * \return the length of src in bytes, including the null terminator.
 *
 * utf8_copy(NULL, src, 0) is equivalent to strlen(src) + 1.
 */
static int usbi_utf8_copy(char *tgt, char const *src, int tgt_size) {
	uint8_t* t = (uint8_t*)tgt;
	uint8_t const* s = (uint8_t const*)src;

	if (NULL == src) {
		if ((NULL != tgt) && (tgt_size > 0)) {
			tgt[0] = 0;
		}
		return 1;
	}

	if ((NULL == tgt) || (tgt_size <= 0)) {
		return (int)(strlen(src) + 1);
	}

	// copy UTF-8 string and compute length
	int k = 0;
	for (k = 0; s[k]; ++k) {
		if (k < tgt_size) {
			t[k] = s[k];
		} else {
			break;
		}
	}

	if (k >= tgt_size) {
		// truncate respecting UTF-8 character boundaries
		int idx = tgt_size - 1;
		while (idx && (0x80 == (t[idx] & 0xC0))) {  // utf-8 continuation byte
			--idx;
		}
		t[idx] = 0;
		return (int)(strlen(src) + 1);
	} else {
		t[k++] = 0;
		return k;
	}
}

/** \ingroup libusb_desc
 * Retrieve a device string without needing to open the device.
 *
 * Since version v1.0.30 \ref LIBUSB_API_VERSION >= 0x0100010C
 *
 * \param dev the target device
 * \param string_type the string type to retrieve
 * \param data the data buffer for the UTF-8 encoded string.
 * \param length the size of the data buffer in bytes.  
 *      USB string descriptors cannot be longer than 
 *      LIBUSB_DEVICE_STRING_BYTES_MAX.
 * \returns a negative error code or
 *      the actual string length in bytes including the null terminator.
 * \see libusb_get_string_descriptor()
 * \see libusb_get_string_descriptor_ascii()
 * 
 * This function works when the device is still closed since it relies
 * on the operating system to provide the string.  The operating system
 * normally reads and caches the common string descriptors during
 * USB enumeration.
 *
 * Since the USB string descriptor could be processed by the OS,
 * this function returns a UTF-8 encoded string.
 *
 * The string will be returned untranslated or in the default OS language
 * when supported by the OS and USB device.
 * 
 * One way to call this function is using a buffer on the stack:
 * 
 *     char buffer[LIBUSB_DEVICE_STRING_BYTES_MAX];
 *     int ret = libusb_get_device_string(dev, LIBUSB_DEVICE_STRING_SERIAL_NUMBER, buffer, sizeof(buffer));
 *     if (ret < 0) {
 *         // handle error
 *     }
 * 
 * This function is commonly used to get the serial number to allow
 * for device selection before opening the selected device.
 */
int API_EXPORTED libusb_get_device_string(libusb_device *dev,
	enum libusb_device_string_type string_type, char *data, int length) {
	char * s;
	if (NULL == dev) {
		return LIBUSB_ERROR_INVALID_PARAM;
	}
	if ((string_type < 0) || (string_type >= LIBUSB_DEVICE_STRING_COUNT)) {
		return LIBUSB_ERROR_INVALID_PARAM;
	}
	if (length <= 0) {
		return LIBUSB_ERROR_INVALID_PARAM;
	}
	if (NULL == data) {
		length = 0;
		data = NULL;
	} else if (length > 0) {
		*data = 0;  // return an empty string on errors when possible
	}

	if (NULL == dev->device_strings_utf8[string_type]) {
		if (usbi_backend.get_device_string) {
			s = malloc(LIBUSB_DEVICE_STRING_BYTES_MAX);
			int rv = usbi_backend.get_device_string(dev, string_type, s, LIBUSB_DEVICE_STRING_BYTES_MAX);
			if (rv < 0) {
				free(s);
				return rv;
			} else {
				dev->device_strings_utf8[string_type] = s;
			}
		} else {
			return LIBUSB_ERROR_NOT_SUPPORTED;
		}
	}

	s = dev->device_strings_utf8[string_type];
	if (NULL == s) {
		return LIBUSB_ERROR_NOT_SUPPORTED;
	}

	return usbi_utf8_copy(data, s, length);
}
